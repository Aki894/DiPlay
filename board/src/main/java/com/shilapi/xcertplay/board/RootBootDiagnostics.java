package com.shilapi.xcertplay.board;

import android.content.Context;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

/** Root-only fixed-source export. No URL, file path or command comes from HTTP. */
public final class RootBootDiagnostics {
    private static final String PHONE="com.shihab.diplay.hudtest";
    private static final File ROOT=new File("/data/vendor/wukong-boot");
    private static final AtomicBoolean busy=new AtomicBoolean();
    private static final String[] ROOT_FILES={"boot-id","stages.tsv","logcat.txt","logcat.txt.1","logcat.txt.2",
        "kernel-first.txt","kernel-final.txt","properties-first.txt","properties-final.txt","provision.jsonl","capture-finished","startup-summary.json"};
    private static final String[] APP_FILES={"board.log","board.log.1","board.log.2","board-health.jsonl",
        "board-health.previous.jsonl","board-startup.json"};
    private static final int FILE_LIMIT=256*1024;
    public static synchronized void event(String name) {
        String line="BOOT_STAGE elapsedMs="+SystemClock.elapsedRealtime()+" "+name;
        android.util.Log.i("WuKongProvision",line);
        File folder=new File(ROOT,"current"),target=new File(folder,"provision.jsonl");
        try {
            if(!safeDirectory(folder) || Files.isSymbolicLink(target.toPath()))return;
            if(target.length()>128*1024)return;
            try(FileOutputStream out=new FileOutputStream(target,true)){out.write((redact(line)+"\n").getBytes(StandardCharsets.UTF_8));}
        } catch(IOException ignored) { /* Android logcat still carries the event. */ }
    }
    public static void request(Context context,String requestId) throws IOException {
        if(!requestId.matches("[0-9a-f]{12}"))throw new IOException("Invalid capture ID");
        if(!busy.compareAndSet(false,true))throw new IOException("Boot export already running");
        new Thread(()->{
            File dir=new File("/data/user/0/"+PHONE+"/files");
            File temp=null;
            try {
                if(!context.getSystemService(android.os.UserManager.class).isUserUnlocked())throw new IOException("User is locked");
                if(!safeDirectory(dir))throw new IOException("Unsafe application directory");
                status(dir,"capturing",null,0,requestId);
                temp=File.createTempFile("boot-export-",".zip.tmp",dir);
                int entries=0;
                try(FileOutputStream output=new FileOutputStream(temp);ZipOutputStream zip=new ZipOutputStream(output)) {
                    JSONObject manifest=new JSONObject().put("captureElapsedMs",SystemClock.elapsedRealtime())
                        .put("requestId",requestId).put("bootId",readText(new File("/proc/sys/kernel/random/boot_id"),128))
                        .put("collectorPresent",new File(ROOT,"current/boot-id").isFile())
                        .put("entryLimitBytes",FILE_LIMIT).put("redacted",true);
                    put(zip,"manifest.json",manifest.toString(2));entries++;
                    for(String cycle:new String[]{"current","previous"}) {
                        File folder=new File(ROOT,cycle);
                        if(!safeDirectory(folder))continue;
                        for(String name:ROOT_FILES) {
                            File file=new File(folder,name);
                            if(safeFile(file)) {put(zip,cycle+"/"+name,readText(file,FILE_LIMIT));entries++;}
                        }
                    }
                    for(String name:APP_FILES) {
                        File file=new File(dir,name);
                        if(safeFile(file)) {put(zip,"app/"+name,readText(file,FILE_LIMIT));entries++;}
                    }
                    // Read-only, bounded snapshots also work on the previous system image.
                    put(zip,"live/boot-events.txt",command("/system/bin/logcat","-b","events","-d","-t","1500","-v","monotonic"));entries++;
                    put(zip,"live/properties.txt",command("/system/bin/getprop"));entries++;
                    put(zip,"live/kernel.txt",command("/system/bin/dmesg"));entries++;
                    zip.finish();output.flush();output.getFD().sync();
                }
                if(!temp.setReadable(true,false) || !temp.renameTo(new File(dir,"boot-diagnostics.zip")))throw new IOException("Cannot publish diagnostic archive");
                status(dir,"ready",null,entries,requestId);
                event("boot_export_ready entries="+entries);
            } catch(Exception error) {
                try {status(dir,"error",error.getClass().getSimpleName()+": "+error.getMessage(),0,requestId);}catch(Exception ignored){}
                android.util.Log.w("WuKongProvision","Boot export failed",error);
            } finally {if(temp!=null)temp.delete();busy.set(false);}
        },"board-boot-export").start();
    }
    private static boolean safeDirectory(File file) {
        return file.isDirectory() && !Files.isSymbolicLink(file.toPath());
    }
    private static boolean safeFile(File file) {
        return file.isFile() && !Files.isSymbolicLink(file.toPath());
    }
    static String redact(String text) {
        StringBuilder out=new StringBuilder();
        for(String line:text.split("\n",-1)) {
            if(line.toLowerCase(java.util.Locale.ROOT).matches(".*(passphrase|password|token|private[_ -]?key|identity\\.pk8).*")) {
                out.append("<redacted sensitive field>\n");continue;
            }
            out.append(line.replaceAll("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])","<redacted-mac>")).append('\n');
        }
        return out.toString();
    }
    static String readText(File file,int limit) throws IOException {
        try(RandomAccessFile input=new RandomAccessFile(file,"r")) {
            long start=Math.max(0,input.length()-limit);input.seek(start);
            byte[] data=new byte[(int)Math.min(limit,input.length()-start)];input.readFully(data);
            String text=new String(data,StandardCharsets.UTF_8);
            if(start>0) {int newline=text.indexOf('\n');text="<truncated; bounded tail>\n"+(newline<0?text:text.substring(newline+1));}
            return text;
        }
    }
    private static void put(ZipOutputStream zip,String name,String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));zip.write(redact(value).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
    }
    static final class CommandResult {
        final boolean done; final int exit; final String output;
        CommandResult(boolean done,int exit,String output) {this.done=done;this.exit=exit;this.output=output;}
    }
    private static String command(String... args) throws Exception {
        CommandResult result=execute(8,FILE_LIMIT,args);
        return "exit="+(result.done?result.exit:"timeout")+"\n"+result.output;
    }
    static CommandResult execute(int seconds,int limit,String... args) throws Exception {
        java.lang.Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        AtomicBoolean truncated=new AtomicBoolean();
        Thread reader=new Thread(()->{
            try(InputStream input=process.getInputStream()) {
                byte[] buffer=new byte[4096];int count;
                while((count=input.read(buffer))!=-1) {
                    int remaining=limit-bytes.size();
                    if(remaining>0)bytes.write(buffer,0,Math.min(remaining,count));
                    if(count>remaining)truncated.set(true); // Drain without unbounded memory.
                }
            } catch(IOException ignored){}
        },"boot-snapshot-reader");reader.setDaemon(true);reader.start();
        boolean done=process.waitFor(seconds,java.util.concurrent.TimeUnit.SECONDS);
        if(!done)process.destroyForcibly();
        reader.join(1000);
        if(reader.isAlive()) {process.getInputStream().close();reader.join(1000);}
        if(reader.isAlive())return new CommandResult(false,-1,"<snapshot reader did not finish>");
        return new CommandResult(done,done?process.exitValue():-1,"truncated="+truncated.get()+"\n"+bytes.toString(StandardCharsets.UTF_8.name()));
    }
    private static void status(File dir,String state,String error,int entries,String requestId) throws Exception {
        JSONObject value=new JSONObject().put("requestId",requestId).put("bootId",readText(new File("/proc/sys/kernel/random/boot_id"),128).trim()).put("state",state).put("elapsedMs",SystemClock.elapsedRealtime())
            .put("error",error).put("entries",entries);
        File temp=File.createTempFile("boot-export-status-",".tmp",dir);
        try {
            try(FileOutputStream out=new FileOutputStream(temp)) {out.write(value.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            temp.setReadable(true,false);
            if(!temp.renameTo(new File(dir,"boot-export-status.json")))throw new IOException("Cannot publish export status");
        } finally {temp.delete();}
    }
}
