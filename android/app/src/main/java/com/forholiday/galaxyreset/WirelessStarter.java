package com.forholiday.galaxyreset;

import android.content.*;
import android.content.pm.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.muntashirakon.adb.*;
import rikka.shizuku.Shizuku;

/** Starts only the installed Shizuku starter, after the user's own ADB pairing. Never runs a reset. */
final class WirelessStarter {
    static final WirelessStarter INSTANCE=new WirelessStarter();
    final AtomicBoolean busy=new AtomicBoolean();
    volatile String status="";
    private long retryAt;
    private final Handler main=new Handler(Looper.getMainLooper());
    interface Completion { void finish(String error); }
    boolean configured(Context context){return context.getSharedPreferences("wireless_start",Context.MODE_PRIVATE).getBoolean("paired",false);}
    private void configured(Context context,boolean value){context.getSharedPreferences("wireless_start",Context.MODE_PRIVATE).edit().putBoolean("paired",value).commit();}
    static boolean wifi(Context context){
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps=manager.getNetworkCapabilities(manager.getActiveNetwork());
        return caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
    void pair(Context context,int port,String code,Completion callback){
        Context app=context.getApplicationContext();
        if(port<1||port>65535||!code.matches("[0-9]{6}")){callback.finish("페어링 포트와 6자리 코드를 확인해주세요.");return;}
        if(!busy.compareAndSet(false,true)){callback.finish("연결 작업이 진행 중입니다.");return;}
        status="자동 시작용 인증 등록 중…";
        new Thread(()->{
            LocalAdbConnection manager=null;String error=null;
            try {
                manager=new LocalAdbConnection(app);
                if(!manager.pair("127.0.0.1",port,code))throw new IOException("페어링을 완료하지 못했습니다.");
                configured(app,true);retryAt=0;status="자동 시작 설정 완료 · 앱을 열면 연결을 확인합니다.";
            }catch(Exception failure){error="페어링 실패 · 현재 표시된 코드로 다시 시도해주세요.";status=error;}
            finally{if(manager!=null)try{manager.disconnect();}catch(Exception ignored){}}
            String result=error;main.post(()->{busy.set(false);callback.finish(result);});
        },"local-adb-pair").start();
    }
    void start(Context context,boolean force){
        if(!WirelessPolicy.shouldStart(configured(context),Shizuku.pingBinder(),busy.get(),SystemClock.elapsedRealtime(),retryAt,force))return;
        if(!wifi(context)){status="자동 시작 대기 · Wi-Fi에 연결해주세요.";return;}
        if(!busy.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();retryAt=SystemClock.elapsedRealtime()+30000;status="Shizuku 자동 시작 중…";
        new Thread(()->{
            LocalAdbConnection manager=null;
            ScheduledExecutorService watchdog=Executors.newSingleThreadScheduledExecutor();
            try {
                if(Settings.Global.getInt(app.getContentResolver(),"adb_wifi_enabled",0)!=1){
                    if(app.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)!=PackageManager.PERMISSION_GRANTED)
                        throw new IOException("개발자 옵션에서 무선 디버깅을 켜주세요. 최초 PC 설정이 필요할 수 있습니다.");
                    Settings.Global.putInt(app.getContentResolver(),"adb_wifi_enabled",1);
                }
                manager=new LocalAdbConnection(app);
                if(!manager.connectTls(app,12000))throw new IOException("무선 디버깅 연결을 확인해주세요.");
                AdbConnection connection=manager.getAdbConnection();
                watchdog.schedule(()->{try{connection.close();}catch(Exception ignored){}},30,TimeUnit.SECONDS);
                if(!command(manager,"id -u").trim().equals("2000"))throw new IOException("무선 디버깅 shell 권한 확인 실패");
                ApplicationInfo info=app.getPackageManager().getApplicationInfo(ResetPipeline.SHIZUKU,0);
                command(manager,"settings put global adb_allowed_connection_time 0");
                command(manager,ResetPipeline.quote(info.nativeLibraryDir+"/libshizuku.so")+" --apk="+ResetPipeline.quote(info.sourceDir));
                boolean started=false;
                for(int i=0;i<20&&!started;i++){
                    try{started=command(manager,"pidof shizuku_server").trim().matches("[0-9\\s]+");}catch(IOException notYetStarted){if(i==19)throw notYetStarted;}
                    if(!started)Thread.sleep(500);
                }
                if(!started)throw new IOException("Shizuku 시작을 확인하지 못했습니다.");
                for(int i=0;i<40&&!Shizuku.pingBinder();i++)Thread.sleep(250);
                if(!Shizuku.pingBinder())throw new IOException("Shizuku는 시작됐으나 앱 권한 연결을 확인하지 못했습니다. 연결 버튼을 눌러주세요.");
                status="Shizuku 자동 시작 완료";retryAt=0;
            }catch(Exception failure){
                if(WirelessPolicy.authenticationFailure(failure)){
                    configured(app,false);status="자동 시작 인증이 해제됐습니다. 최초 연결 설정을 다시 진행해주세요.";
                }else if(failure instanceof InterruptedException){status="무선 디버깅을 찾지 못했습니다. Wi-Fi와 시스템 허용 창을 확인한 뒤 다시 연결해주세요.";}
                else status=failure.getMessage()==null?"자동 시작 실패 · 연결 설정을 확인해주세요.":failure.getMessage();
            }finally{
                watchdog.shutdownNow();if(manager!=null)try{manager.disconnect();}catch(Exception ignored){}
                main.post(()->{if(Shizuku.pingBinder())status="Shizuku 연결 준비 완료";busy.set(false);ShellConnection.INSTANCE.connect(false);});
            }
        },"shizuku-auto-start").start();
    }
    private static String command(LocalAdbConnection manager,String command) throws Exception {
        String wrapped=command+"; _galaxy_adb_exit=$?; printf '\\nGALAXY_ADB_EXIT:%s\\n' \"$_galaxy_adb_exit\"";
        try(AdbStream stream=manager.openStream("shell:"+wrapped);InputStream input=stream.openInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;
            while(true){
                try{count=input.read(buffer);}catch(IOException closed){if(stream.isClosed()&&"Stream closed.".equals(closed.getMessage()))break;throw closed;}
                if(count==-1)break;
                if(output.size()+count>65536)throw new IOException("연결 확인 응답이 너무 큽니다.");output.write(buffer,0,count);
            }
            return WirelessPolicy.commandOutput(output.toString(StandardCharsets.UTF_8.name()));
        }
    }
}
