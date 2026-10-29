package com.forholiday.galaxyreset;

import android.app.*;
import android.content.*;
import android.os.*;
import io.github.muntashirakon.adb.android.AdbMdns;

/** Keeps discovery alive while the operator opens Android's pairing-code dialog. */
public class AdbPairingService extends Service {
    static final String START="local_adb.START", SUBMIT="local_adb.SUBMIT", CODE="pairing_code";
    private static final String CHANNEL="wireless_pairing";
    private static final int NOTIFICATION=31;
    static final int COMPLETION_NOTIFICATION=32; // Remove completion notices left by older versions.
    private final Handler main=new Handler(Looper.getMainLooper());
    private AdbMdns discovery;
    private volatile int port=-1;
    private boolean submitting;
    private final Runnable timeout=()->{WirelessStarter.INSTANCE.status="페어링 대기가 끝났습니다. 자동 시작 설정을 다시 눌러주세요.";stopSelf();};

    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"자동 시작 페어링",NotificationManager.IMPORTANCE_DEFAULT));
    }
    private PendingIntent openApp(){return PendingIntent.getActivity(this,31,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    private Notification notification(String message,boolean input){
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_reset)
                .setContentTitle("초기화 앱 자동 시작 설정").setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(openApp()).setOngoing(true).setOnlyAlertOnce(true);
        if(input){
            PendingIntent submit=PendingIntent.getService(this,32,new Intent(this,AdbPairingService.class).setAction(SUBMIT),
                    PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0));
            builder.addAction(new Notification.Action.Builder(null,"페어링 코드 입력",submit)
                    .addRemoteInput(new RemoteInput.Builder(CODE).setLabel("화면에 표시된 6자리 코드").build()).build());
        }
        return builder.build();
    }
    private void notifyState(String text,boolean input){getSystemService(NotificationManager.class).notify(NOTIFICATION,notification(text,input));}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(SUBMIT.equals(intent.getAction())){
            Bundle results=RemoteInput.getResultsFromIntent(intent);
            CharSequence value=results==null?null:results.getCharSequence(CODE);
            String code=value==null?"":value.toString().trim();
            if(submitting)return START_NOT_STICKY;
            if(port<1||!code.matches("[0-9]{6}")){notifyState("현재 시스템 창에 표시된 6자리 코드를 입력해주세요.",port>0);return START_NOT_STICKY;}
            submitting=true;notifyState("인증 등록 중…",false);
            WirelessStarter.INSTANCE.pair(this,port,code,error->{
                submitting=false;
                if(error==null){
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    getSystemService(NotificationManager.class).cancel(COMPLETION_NOTIFICATION);
                    stopSelf();
                }else notifyState(error,true);
            });
            return START_NOT_STICKY;
        }
        if(!START.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        startForeground(NOTIFICATION,notification("개발자 옵션 → 무선 디버깅 → 페어링 코드로 기기 페어링을 눌러주세요.",false),
                Build.VERSION.SDK_INT>=34?android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE:0);
        if(discovery==null){
            discovery=new AdbMdns(this,AdbMdns.SERVICE_TYPE_TLS_PAIRING,(host,found)->main.post(()->{
                if(submitting)return;
                port=found;
                notifyState(found>0?"페어링 창을 찾았습니다. 표시된 6자리 코드를 입력해주세요.":"페어링 코드 창을 다시 열어주세요.",found>0);
            }));
            discovery.start();
        }
        main.removeCallbacks(timeout);main.postDelayed(timeout,150000);
        return START_NOT_STICKY;
    }
    @Override public void onTimeout(int startId){stopSelf();}
    @Override public void onTimeout(int startId,int fgsType){stopSelf();}
    @Override public void onDestroy(){
        main.removeCallbacksAndMessages(null);
        if(discovery!=null)try{discovery.stop();}catch(Exception ignored){}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
