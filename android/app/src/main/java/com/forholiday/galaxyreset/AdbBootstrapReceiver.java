package com.forholiday.galaxyreset;

import android.content.*;

/** Initial USB setup only: DUMP is held by the already-authorized ADB shell, not ordinary apps. */
public class AdbBootstrapReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        boolean check = "com.forholiday.galaxyreset.CHECK_ADB_AUTH_FROM_USB".equals(intent.getAction());
        if(!check && !"com.forholiday.galaxyreset.PAIR_FROM_USB".equals(intent.getAction()))return;
        if(ResetService.alive||new RunState(context).phase().equals("running")||AppUpdater.INSTANCE.active()){
            setResultCode(1);setResultData("BUSY");return;
        }
        if (check) {
            if (!WirelessStarter.INSTANCE.configured(context)) { setResultCode(1);setResultData("NO_CREDENTIAL");return; }
            PendingResult pending = goAsync();
            new Thread(() -> {
                LocalAdbConnection manager = null;
                boolean authenticated = false;
                try {
                    manager = new LocalAdbConnection(context.getApplicationContext());
                    authenticated = manager.connectTls(context.getApplicationContext(), 5000);
                } catch (Exception ignored) {}
                finally {
                    if (manager != null) try { manager.disconnect(); } catch (Exception ignored) {}
                    pending.setResultCode(authenticated ? 0 : 1);
                    pending.setResultData(authenticated ? "AUTHENTICATED" : "AUTH_REQUIRED");pending.finish();
                }
            }, "usb-auth-check").start();
            return;
        }
        int port=intent.getIntExtra("port",-1);String code=intent.getStringExtra("code");
        if(port<1||port>65535||code==null||!code.matches("[0-9]{6}")){setResultCode(1);setResultData("INVALID");return;}
        PendingResult pending=goAsync();
        WirelessStarter.INSTANCE.pair(context,port,code,error->{pending.setResultCode(error==null?0:1);pending.setResultData(error==null?"PAIRED":"PAIR_FAILED");pending.finish();});
    }
}
