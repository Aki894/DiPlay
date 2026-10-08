package com.shilapi.xcertplay.board;

import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;

/** Fixed Android 13 root entry point, launched by vendor init, not an HTTP shell. */
public final class BoardProvisioner {
    private static final String PHONE="com.shihab.diplay.hudtest",CAR="com.projection.car";
    private static volatile long pairingUntil;
    private static Context context;
    // ReceiverDispatcher's Binder holds a weak reference; keep it alive for this process.
    private static Object pairingDispatcher;
    private static boolean pairingReceiverReady;
    private static final long[] taskLogAt=new long[3];
    // This entry is executed only by root app_process, not as a targetSdk APK process.
    // Android 13 app_process does not apply the APK's target-SDK hidden-API cutoff.
    @android.annotation.SuppressLint({"SoonBlockedPrivateApi","BlockedPrivateApi"})
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=0) throw new SecurityException("Root provisioning only");
        Looper.prepareMainLooper();
        Class<?> thread=Class.forName("android.app.ActivityThread");
        Object main=thread.getMethod("systemMain").invoke(null);
        // systemMain creates the Context without attaching an application to AMS.
        // This helper is nevertheless a separate Binder client, not system_server.
        // UsbDevice/UsbAccessory reject remote IUsbSerialReader proxies when isSystem()
        // is true. Restore the correct client flag before receiving any USB parcel.
        Field systemThread=thread.getDeclaredField("mSystemThread");
        systemThread.setAccessible(true);
        systemThread.setBoolean(main,false);
        context=(Context)thread.getMethod("getSystemContext").invoke(main);
        // Android 13's default AppOps message sampler reports SyncNotedAppOp
        // through an app-only Binder path that requires a non-null package name.
        // app_process has no bound application, so getAddress() otherwise fails
        // in Parcel.readExceptionCode before its Bluetooth result is delivered.
        // Disable ONLY this root process's optional stack-trace sampling. The
        // system-side permission checks and AppOps accounting remain active.
        Class<?> sampling=Class.forName("com.android.internal.app.MessageSamplingConfig");
        Object noSampling=sampling.getConstructor(int.class,int.class,long.class)
                .newInstance(-1 /* Android 13 OP_NONE */,0,Long.MAX_VALUE);
        Field samplingConfig=AppOpsManager.class.getDeclaredField("sConfig");
        samplingConfig.setAccessible(true);
        samplingConfig.set(null,noSampling);
        android.util.Log.i("WuKongProvision","Root AppOps stack sampling disabled");
        provision();
        // USB authorization must run even if optional Bluetooth setup is unavailable.
        try { registerPairingReceiver(); pairingReceiverReady=true; }
        catch(Exception e) { android.util.Log.w("WuKongProvision","Pairing receiver unavailable; USB loop continues",rootCause(e)); }
        android.util.Log.i("WuKongProvision","USB permission loop ready");
        Handler handler=new Handler(Looper.getMainLooper());
        handler.post(new Runnable() { public void run() {
            for(int task=0;task<3;task++) try {
                if(task==0)grantUsb();else if(task==1)bluetoothAddress();else maintenance();
            } catch(Exception e) {
                long now=SystemClock.elapsedRealtime();
                if(taskLogAt[task]==0 || now-taskLogAt[task]>=30000) {
                    taskLogAt[task]=now;
                    android.util.Log.w("WuKongProvision","Task "+task+" retry",rootCause(e));
                }
            }
            handler.postDelayed(this,2000);
        }});
        Looper.loop();
    }
    private static Throwable rootCause(Throwable e) {
        while(e instanceof InvocationTargetException && e.getCause()!=null)e=e.getCause();
        return e;
    }
    private static void registerPairingReceiver() throws Exception {
        BroadcastReceiver receiver=new BroadcastReceiver() {
            @Override public void onReceive(Context c,Intent i) {
                if(pairingUntil==0 || SystemClock.elapsedRealtime()>pairingUntil) return;
                BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int variant=i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT,-1);
                // Numeric comparison/consent only. Never invent or silently inject a PIN/passkey.
                if(d!=null && (variant==2 || variant==3)) try {
                    BluetoothDevice.class.getMethod("setPairingConfirmation",boolean.class).invoke(d,true);
                } catch(Exception e) { android.util.Log.w("WuKongProvision","Pair confirmation unavailable",e); }
            }
        };
        // app_process is not an AMS-managed application. Passing ActivityThread's
        // IApplicationThread via Context.registerReceiver causes "Unable to find app".
        // Use the Android 13 Binder API's supported null-caller path for root processes.
        Class<?> dispatcherClass=Class.forName("android.app.LoadedApk$ReceiverDispatcher");
        Constructor<?> constructor=dispatcherClass.getDeclaredConstructor(
                BroadcastReceiver.class,Context.class,Handler.class,Instrumentation.class,boolean.class);
        constructor.setAccessible(true);
        pairingDispatcher=constructor.newInstance(receiver,context,new Handler(Looper.getMainLooper()),null,true);
        Method getReceiver=dispatcherClass.getDeclaredMethod("getIIntentReceiver");
        getReceiver.setAccessible(true);
        Object binderReceiver=getReceiver.invoke(pairingDispatcher);
        Object manager=ActivityManager.class.getMethod("getService").invoke(null);
        Class.forName("android.app.IActivityManager").getMethod("registerReceiverWithFeature",
                Class.forName("android.app.IApplicationThread"),String.class,String.class,String.class,
                Class.forName("android.content.IIntentReceiver"),IntentFilter.class,String.class,int.class,int.class)
                .invoke(manager,null,null,null,null,binderReceiver,
                        new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST),null,0,Context.RECEIVER_EXPORTED);
    }
    private static void run(String... args) throws Exception {
        java.lang.Process p=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(new File("/dev/null")).start();
        if(!p.waitFor(20,java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();throw new IOException("Provisioning command timed out: "+args[0]);
        }
        if(p.exitValue()!=0) android.util.Log.w("WuKongProvision","Command unavailable: "+args[0]+" "+args[1]);
    }
    private static void provision() throws Exception {
        for(String pkg:new String[]{PHONE,CAR}) {
            String[] perms=pkg.equals(PHONE)?new String[]{"RECORD_AUDIO","BLUETOOTH_CONNECT","ACCESS_FINE_LOCATION","ACCESS_COARSE_LOCATION","NEARBY_WIFI_DEVICES","POST_NOTIFICATIONS"}:new String[]{"POST_NOTIFICATIONS"};
            for(String permission:perms) run("/system/bin/pm","grant",pkg,"android.permission."+permission);
            // Wi-Fi Direct on the pinned SDK 32 build checks location AppOps.
            // A displayless service starts from boot/web, so grant background
            // access AFTER coarse/fine instead of fighting PermissionManager's
            // per-UID foreground mode with temporary appops overrides.
            if(pkg.equals(PHONE) && Build.VERSION.SDK_INT>=29 && Build.VERSION.SDK_INT<=32)
                run("/system/bin/pm","grant",pkg,"android.permission.ACCESS_BACKGROUND_LOCATION");
            run("/system/bin/cmd","deviceidle","whitelist","+"+pkg);
            run("/system/bin/cmd","appops","set",pkg,"RUN_IN_BACKGROUND","allow");
        }
        run("/system/bin/cmd","appops","set",PHONE,"ACTIVATE_VPN","allow");
        run("/system/bin/pm","disable-user","--user","0","com.android.mtp");
        run("/system/bin/am","force-stop","com.android.mtp");
        run("/system/bin/svc","bluetooth","enable");
        run("/system/bin/svc","wifi","enable");
    }
    private static Object usb() throws Exception {
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"usb");
        return Class.forName("android.hardware.usb.IUsbManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
    }
    private static void invoke(Object service,String name,Class<?>[] types,Object... args) throws Exception {
        Class.forName("android.hardware.usb.IUsbManager").getMethod(name,types).invoke(service,args);
    }
    private static void grantUsb() throws Exception {
        Object service=usb();Bundle devices=new Bundle();
        invoke(service,"getDeviceList",new Class<?>[]{Bundle.class},devices);
        int phoneUid=context.getPackageManager().getApplicationInfo(PHONE,0).uid;
        for(String key:devices.keySet()) {
            UsbDevice device=devices.getParcelable(key);
            if(device!=null && device.getVendorId()==0x05ac) {
                invoke(service,"grantDevicePermission",new Class<?>[]{UsbDevice.class,int.class},device,phoneUid);
                invoke(service,"setDevicePackage",new Class<?>[]{UsbDevice.class,String.class,int.class},device,PHONE,0);
            }
        }
        UsbAccessory accessory=(UsbAccessory)Class.forName("android.hardware.usb.IUsbManager").getMethod("getCurrentAccessory").invoke(service);
        if(accessory!=null && "Baidu".equals(accessory.getManufacturer()) && "CarLife".equals(accessory.getModel())) {
            int carUid=context.getPackageManager().getApplicationInfo(CAR,0).uid;
            invoke(service,"grantAccessoryPermission",new Class<?>[]{UsbAccessory.class,int.class},accessory,carUid);
            invoke(service,"setAccessoryPackage",new Class<?>[]{UsbAccessory.class,String.class,int.class},accessory,CAR,0);
        }
    }
    private static void bluetoothAddress() throws Exception {
        File target=new File("/data/user/0/"+PHONE+"/files/board-bluetooth-address");
        if(!target.getParentFile().isDirectory())return;
        BluetoothAdapter adapter=context.getSystemService(BluetoothManager.class).getAdapter();
        if(adapter==null || !adapter.isEnabled())return;
        String address=(String)BluetoothAdapter.class.getMethod("getAddress").invoke(adapter);
        if(address==null || !address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") || address.startsWith("02:00:00:00:00:") || address.equals("00:00:00:00:00:00"))return;
        if(target.isFile() && address.equals(new String(java.nio.file.Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8).trim()))return;
        File temp=File.createTempFile("bt-address-", ".tmp",target.getParentFile());
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(address.getBytes(StandardCharsets.UTF_8));}
        temp.setReadable(true,false);if(!temp.renameTo(target))temp.delete();
    }
    private static void maintenance() throws Exception {
        File dir=new File("/data/user/0/"+PHONE+"/files");
        File request=new File(dir,"maintenance-request");
        if(!request.isFile() || request.length()>32 || java.nio.file.Files.isSymbolicLink(request.toPath())) return;
        String command=new String(java.nio.file.Files.readAllBytes(request.toPath()),StandardCharsets.UTF_8).trim();
        if(!request.delete()) return;
        String result="completed";
        try {
            if("pair".equals(command)) {
                if(!pairingReceiverReady) {
                    registerPairingReceiver();
                    pairingReceiverReady=true;
                }
                setDiscoverable(true);
                pairingUntil=SystemClock.elapsedRealtime()+120000;
                result="discoverable requested for 120 seconds";
                new Handler(Looper.getMainLooper()).postDelayed(()->{if(SystemClock.elapsedRealtime()>=pairingUntil)try{setDiscoverable(false);}catch(Exception ignored){}},120000);
            } else if("pair-stop".equals(command)) {pairingUntil=0;setDiscoverable(false);}
            else if(command.matches("forget:(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) {
                String address=command.substring(7);
                BluetoothAdapter adapter=context.getSystemService(BluetoothManager.class).getAdapter();
                BluetoothDevice device=adapter.getRemoteDevice(address);
                int before=device.getBondState();
                if(before==BluetoothDevice.BOND_NONE) {
                    result="already unpaired";
                } else {
                    String method=before==BluetoothDevice.BOND_BONDING?"cancelBondProcess":"removeBond";
                    Object accepted=BluetoothDevice.class.getMethod(method).invoke(device);
                    int after=device.getBondState();
                    if(!Boolean.TRUE.equals(accepted) && after!=BluetoothDevice.BOND_NONE)
                        throw new IOException(method+" rejected; bondState="+before+" -> "+after);
                    result=after==BluetoothDevice.BOND_NONE?"unpaired":"removal requested";
                }
            }
            else if("reboot".equals(command)) run("/system/bin/reboot");
            else if("display-off".equals(command) || "display-on".equals(command)) display("display-on".equals(command));
            else result="unsupported action";
        } catch(Exception e) {
            Throwable cause=rootCause(e);
            if("pair".equals(command))pairingUntil=0;
            result="failed: "+cause.getClass().getSimpleName()+": "+String.valueOf(cause.getMessage());
            android.util.Log.w("WuKongProvision","Maintenance "+command+" failed",cause);
        }
        File temp=File.createTempFile("maintenance-", ".tmp",dir);
        try(FileOutputStream out=new FileOutputStream(temp)){out.write((command+": "+result).getBytes(StandardCharsets.UTF_8));}
        temp.setReadable(true,false);
        if(!temp.renameTo(new File(dir,"maintenance-result")))temp.delete();
    }
    private static void setDiscoverable(boolean enabled) throws Exception {
        BluetoothAdapter adapter=context.getSystemService(BluetoothManager.class).getAdapter();
        if(adapter==null || !adapter.isEnabled())
            throw new IllegalStateException("Bluetooth is not ON; cannot change discoverability");
        Object status=BluetoothAdapter.class.getMethod("setScanMode",int.class).invoke(adapter,
                enabled?BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE:BluetoothAdapter.SCAN_MODE_CONNECTABLE);
        // The pinned Tiramisu preview (SDK 32) returns boolean; released API 33
        // returns BluetoothStatusCodes. Inspect the actual result, not SDK_INT.
        boolean accepted=Boolean.TRUE.equals(status)
                || (status instanceof Integer && ((Integer)status)==BluetoothStatusCodes.SUCCESS);
        if(!accepted)
            throw new IOException("Bluetooth setScanMode rejected, status="+status);
    }
    private static void display(boolean enabled) throws Exception {
        Class<?> surface=Class.forName("android.view.SurfaceControl");
        long[] ids=(long[])surface.getMethod("getPhysicalDisplayIds").invoke(null);
        if(ids.length==0) throw new IllegalStateException("No physical display");
        for(long id:ids) {
            IBinder token=(IBinder)surface.getMethod("getPhysicalDisplayToken",long.class).invoke(null,id);
            surface.getMethod("setDisplayPowerMode",IBinder.class,int.class).invoke(null,token,enabled?2:0);
        }
    }
}
