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
    private static final long[] taskLogAt=new long[3];
    // This entry is executed only by root app_process, not as a targetSdk APK process.
    // Android 13 app_process does not apply the APK's target-SDK hidden-API cutoff.
    @android.annotation.SuppressLint({"SoonBlockedPrivateApi","BlockedPrivateApi"})
    public static void main(String[] args) throws Exception {
        if(android.os.Process.myUid()!=0) throw new SecurityException("Root provisioning only");
        event("helper_main");
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
        if(context.getSystemService(UserManager.class).isUserUnlocked())pairingWindow(0);
        // Radio requests do not depend on CE secrets or application grants.
        startRadio("wifi");
        startRadio("bluetooth");
        event("waiting_user_unlock");
        new Thread(() -> {
            while(true) {
                try {
                    if(!context.getSystemService(UserManager.class).isUserUnlocked()) {Thread.sleep(1000);continue;}
                    event("user_unlocked");
                    provisionPhonePermissions();event("phone_permissions_ready");
                    runChecked("/system/bin/am","start-foreground-service","-n",PHONE+"/com.shilapi.xcertplay.board.BoardService","--es","command","boot");
                    event("phone_service_requested");
                    // Noncritical maintenance cannot delay the phone service request.
                    provisionMaintenance();
                    runChecked("/system/bin/am","start-foreground-service","-n",CAR+"/.BoardSessionService","--es","command","start");
                    event("car_service_requested");
                    android.util.Log.i("WuKongProvision","Provisioning ready elapsedMs="+SystemClock.elapsedRealtime());
                    break;
                } catch(Exception e) {android.util.Log.w("WuKongProvision","Provisioning retry",e);}
                try {Thread.sleep(2000);}catch(InterruptedException e){return;}
            }
        },"board-provision").start();
        android.util.Log.i("WuKongProvision","USB permission loop ready");
        Handler handler=new Handler(Looper.getMainLooper());
        handler.post(new Runnable() { public void run() {
            for(int task=0;task<3;task++) try {
                if(!context.getSystemService(UserManager.class).isUserUnlocked())continue;
                if(task==0)grantUsb();else if(task==1)bluetoothAddress();else {maintenance();confirmPairing();}
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
    private static void pairingWindow(long until) throws Exception {
        pairingUntil=until;
        writePairingFile("pairing-window",Long.toString(until));
    }
    private static void writePairingFile(String name,String value) throws Exception {
        File dir=new File("/data/user/0/"+PHONE+"/files");
        if(!dir.isDirectory())return;
        File temp=File.createTempFile("pairing-", ".tmp",dir);
        try {
            try(FileOutputStream out=new FileOutputStream(temp)){out.write(value.getBytes(StandardCharsets.UTF_8));}
            temp.setReadable(true,false);
            if(!temp.renameTo(new File(dir,name)))throw new IOException("Cannot write "+name);
        } finally {temp.delete();}
    }
    private static void confirmPairing() throws Exception {
        File request=new File("/data/user/0/"+PHONE+"/files/pairing-request");
        if(!request.isFile() || request.length()>256 || java.nio.file.Files.isSymbolicLink(request.toPath()))return;
        String text=new String(java.nio.file.Files.readAllBytes(request.toPath()),StandardCharsets.UTF_8);
        if(!request.delete())return;
        try {
            org.json.JSONObject data=new org.json.JSONObject(text);
            long until=data.getLong("until");
            if(pairingUntil==0 || until!=pairingUntil || SystemClock.elapsedRealtime()>=pairingUntil)
                throw new IOException("Pairing window expired or replaced");
            int variant=data.getInt("variant");
            String address=data.getString("address");
            if((variant!=2 && variant!=3) || !address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))
                throw new IOException("Unsupported pairing request");
            // Resolve a fresh local device rather than retaining a broadcast parcel's attribution.
            BluetoothAdapter adapter=context.getSystemService(BluetoothManager.class).getAdapter();
            if(adapter==null || !adapter.isEnabled())throw new IOException("Bluetooth is not ON");
            BluetoothDevice device=adapter.getRemoteDevice(address);
            if(device.getBondState()!=BluetoothDevice.BOND_BONDING)throw new IOException("Device is not pairing");
            Object accepted=BluetoothDevice.class.getMethod("setPairingConfirmation",boolean.class).invoke(device,true);
            if(!Boolean.TRUE.equals(accepted))throw new IOException("Pairing confirmation rejected: "+accepted);
            writePairingFile("pairing-result","confirmation sent; waiting for iPhone");
            android.util.Log.i("WuKongProvision","Pair confirmation accepted; variant="+variant);
        } catch(SecurityException e) { pairingFailure(e); }
        catch(Exception e) { pairingFailure(rootCause(e)); }
    }
    private static void pairingFailure(Throwable cause) throws Exception {
        writePairingFile("pairing-result","failed: "+cause.getClass().getSimpleName()+": "+String.valueOf(cause.getMessage()));
        android.util.Log.w("WuKongProvision","Pair confirmation failed",cause);
    }
    private static void event(String name) { RootBootDiagnostics.event(name); }
    private static void run(String... args) throws Exception { command(false,args); }
    private static void runChecked(String... args) throws Exception { command(true,args); }
    private static void command(boolean checked,String... args) throws Exception {
        long begin=SystemClock.elapsedRealtime();
        String label=String.join(" ",args);
        event("command_start "+label);
        RootBootDiagnostics.CommandResult result=RootBootDiagnostics.execute(20,4096,args);
        if(!result.done) {
            event("command_timeout durationMs="+(SystemClock.elapsedRealtime()-begin)+" "+label);
            throw new IOException("Provisioning command timed out: "+args[0]);
        }
        event("command_end durationMs="+(SystemClock.elapsedRealtime()-begin)+" exit="+result.exit+" "+label);
        if(result.exit!=0) {
            event("command_error "+label+" output="+result.output);
            if(checked)throw new IOException("Required provisioning command failed exit="+result.exit+": "+label);
            android.util.Log.w("WuKongProvision","Optional command failed: "+label);
        }
    }

    private static void startRadio(String radio) {
        new Thread(() -> {
            long requestedAt=0;
            while(true)try {
                boolean on,off;
                if(radio.equals("wifi")) {
                    android.net.wifi.WifiManager wifi=context.getSystemService(android.net.wifi.WifiManager.class);
                    if(wifi==null)throw new IOException("Wi-Fi framework not ready");
                    on=wifi.isWifiEnabled();off=wifi.getWifiState()==android.net.wifi.WifiManager.WIFI_STATE_DISABLED;
                } else {
                    BluetoothManager manager=context.getSystemService(BluetoothManager.class);
                    BluetoothAdapter adapter=manager==null?null:manager.getAdapter();
                    if(adapter==null)throw new IOException("Bluetooth framework not ready");
                    on=adapter.isEnabled();off=adapter.getState()==BluetoothAdapter.STATE_OFF;
                }
                if(on) {event(radio+"_observed_on");return;}
                if(requestedAt==0 || (off && SystemClock.elapsedRealtime()-requestedAt>=30000)) {
                    runChecked("/system/bin/svc",radio,"enable");
                    requestedAt=SystemClock.elapsedRealtime();event(radio+"_enable_requested");
                }
                // Do not spam enable while a slow controller is initializing.
                Thread.sleep(1000);
            } catch(Exception e) {
                android.util.Log.w("WuKongProvision",radio+" readiness retry",e);
                try {Thread.sleep(3000);}catch(InterruptedException interrupted){return;}
            }
        },"board-"+radio).start();
    }
    private static void provisionPhonePermissions() throws Exception {
        for(String permission:new String[]{"RECORD_AUDIO","BLUETOOTH_CONNECT","ACCESS_FINE_LOCATION","ACCESS_COARSE_LOCATION","NEARBY_WIFI_DEVICES","POST_NOTIFICATIONS"})
            grantMissing(PHONE,permission);
        if(Build.VERSION.SDK_INT>=29 && Build.VERSION.SDK_INT<=32)grantMissing(PHONE,"ACCESS_BACKGROUND_LOCATION");
    }
    private static void provisionMaintenance() throws Exception {
        grantMissing(CAR,"POST_NOTIFICATIONS");
        for(String pkg:new String[]{PHONE,CAR}) {
            if(!context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(pkg))
                run("/system/bin/cmd","deviceidle","whitelist","+"+pkg);
            allowOp(pkg,"RUN_IN_BACKGROUND");
        }
        allowOp(PHONE,"ACTIVATE_VPN");
        try {
            if(context.getPackageManager().getApplicationEnabledSetting("com.android.mtp")!=android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
                run("/system/bin/pm","disable-user","--user","0","com.android.mtp");
        } catch(IllegalArgumentException absent) {event("mtp_package_absent");}
    }
    private static void allowOp(String pkg,String op) throws Exception {
        int uid=context.getPackageManager().getApplicationInfo(pkg,0).uid;
        if(Build.VERSION.SDK_INT<29 || context.getSystemService(AppOpsManager.class).unsafeCheckOpNoThrow("android:"+op.toLowerCase(java.util.Locale.ROOT),uid,pkg)!=AppOpsManager.MODE_ALLOWED)
            run("/system/bin/cmd","appops","set",pkg,op,"allow");
    }
    private static void grantMissing(String pkg,String name) throws Exception {
        String permission="android.permission."+name;
        // Tiramisu preview reports SDK 32 but already contains some API-33
        // permissions. Ask this framework rather than guessing from SDK_INT.
        try {context.getPackageManager().getPermissionInfo(permission,0);}
        catch(android.content.pm.PackageManager.NameNotFoundException missing) {return;}
        String[] requested=context.getPackageManager().getPackageInfo(pkg,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
        if(requested==null || !java.util.Arrays.asList(requested).contains(permission))return;
        if(context.getPackageManager().checkPermission(permission,pkg)==android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        runChecked("/system/bin/pm","grant","--user","0",pkg,permission);
        if(context.getPackageManager().checkPermission(permission,pkg)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Permission grant not effective: "+permission);
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
                // Publish the window before making the controller discoverable.
                pairingWindow(SystemClock.elapsedRealtime()+120000);
                writePairingFile("pairing-result","waiting for pairing request");
                setDiscoverable(true);
                result="discoverable requested for 120 seconds";
                long lease=pairingUntil;
                new Handler(Looper.getMainLooper()).postDelayed(()->{
                    if(pairingUntil==lease)try{pairingWindow(0);setDiscoverable(false);}catch(Exception e){android.util.Log.w("WuKongProvision","Pairing window close failed",e);}
                },120000);
            } else if("pair-stop".equals(command)) {pairingWindow(0);setDiscoverable(false);}
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
            else if(command.matches("boot-export:[0-9a-f]{12}")) {RootBootDiagnostics.request(context,command.substring(12));result="capture queued";}
            else if("reboot".equals(command)) run("/system/bin/reboot");
            else if("display-off".equals(command) || "display-on".equals(command)) display("display-on".equals(command));
            else result="unsupported action";
        } catch(SecurityException e) {
            if("pair".equals(command))pairingWindow(0);
            result="failed: SecurityException: "+String.valueOf(e.getMessage());
            android.util.Log.w("WuKongProvision","Maintenance "+command+" permission rejected",e);
        } catch(Exception e) {
            Throwable cause=rootCause(e);
            if("pair".equals(command))pairingWindow(0);
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


