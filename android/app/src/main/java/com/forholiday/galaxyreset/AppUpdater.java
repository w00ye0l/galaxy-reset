package com.forholiday.galaxyreset;

import android.app.PendingIntent;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

final class AppUpdater {
    static final AppUpdater INSTANCE=new AppUpdater();
    static final String META_NAME=UpdatePolicy.META_NAME, APK_NAME=UpdatePolicy.APK_NAME;
    final AtomicBoolean busy=new AtomicBoolean();
    volatile boolean installPending;
    boolean active(){return busy.get()||installPending;}
    final Handler main=new Handler(Looper.getMainLooper());
    volatile String status="";
    interface Callback<T>{void success(T value);void error(String message);}
    record Release(long version,String name,String hash,long size,String apkUrl){}
    static File directory(Context context){return new File(context.getCacheDir(),"updates");}
    static File apkFile(Context context){return new File(directory(context),APK_NAME);}

    HttpURLConnection connect(String address) throws Exception {
        URI uri=URI.create(address);
        for(int redirect=0;redirect<6;redirect++){
            if(!UpdatePolicy.downloadHost(uri))throw new IOException("허용되지 않은 업데이트 다운로드 주소입니다.");
            HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();
            connection.setConnectTimeout(15000);connection.setReadTimeout(30000);connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent","GalaxyReset/"+BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept","*/*");
            connection.setRequestProperty("Cache-Control","no-cache");
            int code=connection.getResponseCode();
            if(code==301||code==302||code==303||code==307||code==308){String location=connection.getHeaderField("Location");connection.disconnect();if(location==null)throw new IOException("업데이트 다운로드 주소가 없습니다.");uri=uri.resolve(location);continue;}
            if(code!=200){connection.disconnect();throw new IOException(code==404?"게시된 앱 업데이트가 없습니다.":code==403||code==429?"업데이트 서버 요청이 제한됐습니다. 잠시 후 다시 확인하세요.":"업데이트 서버 오류 (HTTP "+code+")");}
            return connection;
        }
        throw new IOException("업데이트 다운로드 이동 횟수를 초과했습니다.");
    }
    JSONObject json(String address,int limit) throws Exception {
        HttpURLConnection connection=connect(address);
        try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1){if(output.size()+n>limit)throw new IOException("업데이트 정보 파일이 너무 큽니다.");output.write(buffer,0,n);}
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        }finally{connection.disconnect();}
    }
    void check(Callback<Release> callback){
        if(!busy.compareAndSet(false,true))return;status="업데이트 확인 중…";
        new Thread(()->{
            try{
                JSONObject metadata=json(UpdatePolicy.LATEST_METADATA,64*1024);
                if(metadata.getInt("schemaVersion")!=1||!BuildConfig.APPLICATION_ID.equals(metadata.getString("packageName"))||!APK_NAME.equals(metadata.getString("apkName")))throw new IOException("앱 업데이트 정보가 일치하지 않습니다.");
                Release next=new Release(metadata.getLong("versionCode"),metadata.getString("versionName"),metadata.getString("sha256"),metadata.getLong("size"),UpdatePolicy.apkForVersion(metadata.getString("versionName")));
                if(!UpdatePolicy.validHash(next.hash)||next.version<1||next.size<1||next.size>UpdatePolicy.MAX_APK_BYTES)throw new IOException("유효하지 않은 앱 업데이트 정보입니다.");
                status=UpdatePolicy.newer(BuildConfig.VERSION_CODE,next.version)?"새 버전 "+next.name+" 사용 가능":"최신 버전입니다.";
                main.post(()->{busy.set(false);callback.success(next);});
            }catch(Exception e){fail(callback,e);}
        },"update-check").start();
    }
    <T> void fail(Callback<T> callback,Exception error){status=error.getMessage()==null?"업데이트 처리 실패":error.getMessage();main.post(()->{busy.set(false);callback.error(status);});}
    static String hash(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[128*1024];int n;
        try(InputStream input=new FileInputStream(file)){while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
        return hex(digest.digest());
    }
    static String hex(byte[] data){StringBuilder result=new StringBuilder();for(byte b:data)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
    void validate(Context context,File apk,Release expected) throws Exception {
        if(apk.length()!=expected.size||!hash(apk).equals(expected.hash))throw new IOException("다운로드 파일 검증 실패 · 다시 다운로드하세요.");
        PackageManager manager=context.getPackageManager();
        PackageInfo archive=manager.getPackageArchiveInfo(apk.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo installed=manager.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(archive==null||!UpdatePolicy.archiveMatches(context.getPackageName(),installed.getLongVersionCode(),expected.version,archive.packageName,archive.getLongVersionCode()))throw new IOException("설치할 앱의 패키지 또는 버전이 일치하지 않습니다.");
        if(archive.signingInfo==null||installed.signingInfo==null)throw new IOException("앱 서명을 확인하지 못했습니다.");
        Set<String> original=new HashSet<>(),incoming=new HashSet<>();
        for(Signature signature:installed.signingInfo.getApkContentsSigners())original.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        for(Signature signature:archive.signingInfo.getApkContentsSigners())incoming.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        if(!UpdatePolicy.sameSigners(original,incoming))throw new IOException("기존 앱과 서명이 다릅니다. 같은 서명 키로 배포한 APK가 필요합니다.");
    }
    void download(Context context,Release release,Runnable progress,Callback<File> callback){
        if(!busy.compareAndSet(false,true))return;Context app=context.getApplicationContext();status="다운로드 준비 중…";
        new Thread(()->{
            File directory=directory(app),part=new File(directory,"update.part"),target=apkFile(app);
            try{
                if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("업데이트 저장 공간을 준비하지 못했습니다.");
                if(target.isFile()&&target.length()==release.size&&hash(target).equals(release.hash)){validate(app,target,release);status="다운로드·서명 검증 완료";main.post(()->{busy.set(false);callback.success(target);});return;}
                if(directory.getUsableSpace()<release.size*2+32L*1024*1024)throw new IOException("업데이트 설치를 위한 저장 공간이 부족합니다.");
                HttpURLConnection connection=connect(release.apkUrl);
                try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(part)){
                    byte[] buffer=new byte[128*1024];long total=0,deadline=SystemClock.elapsedRealtime()+15*60*1000;int n,last=-1;
                    while((n=input.read(buffer))!=-1){total+=n;if(total>release.size||SystemClock.elapsedRealtime()>deadline)throw new IOException("다운로드 크기 또는 제한 시간을 초과했습니다.");output.write(buffer,0,n);
                        int percent=(int)(total*100/release.size);if(percent!=last){last=percent;status="다운로드 중 "+percent+"%";main.post(progress);}}
                    output.getFD().sync();
                }finally{connection.disconnect();}
                validate(app,part,release);
                if(target.exists()&&!target.delete())throw new IOException("이전 다운로드를 정리하지 못했습니다.");
                if(!part.renameTo(target))throw new IOException("업데이트 파일 저장 실패");
                status="다운로드·서명 검증 완료";main.post(()->{busy.set(false);callback.success(target);});
            }catch(Exception e){part.delete();fail(callback,e);}
        },"update-download").start();
    }
    void install(Context context,Release release,Callback<Void> callback){
        if(!busy.compareAndSet(false,true))return;Context app=context.getApplicationContext();status="설치 준비 중…";
        new Thread(()->{
            int sessionId=-1;boolean committed=false;PackageInstaller installer=app.getPackageManager().getPackageInstaller();
            try{
                File file=apkFile(app);validate(app,file,release);
                if(!app.getPackageManager().canRequestPackageInstalls())throw new IOException("이 앱의 업데이트 설치 권한을 허용해주세요.");
                PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());params.setSize(file.length());
                if(Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
                sessionId=installer.createSession(params);
                try(PackageInstaller.Session session=installer.openSession(sessionId)){
                    try(InputStream input=new FileInputStream(file);OutputStream output=session.openWrite("base.apk",0,file.length())){
                        byte[] buffer=new byte[128*1024];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);session.fsync(output);
                    }
                    Intent result=new Intent(app,UpdateInstallReceiver.class).setAction(app.getPackageName()+".UPDATE_INSTALL").putExtra("session_id",sessionId);
                    int flags=PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=31?PendingIntent.FLAG_MUTABLE:0);
                    PendingIntent pending=PendingIntent.getBroadcast(app,sessionId,result,flags);
                    installPending=true;session.commit(pending.getIntentSender());committed=true;
                }
                status="Android 설치 확인창을 확인해주세요.";
                main.post(()->{busy.set(false);callback.success(null);});
            }catch(Exception e){installPending=false;if(sessionId>=0&&!committed)try{installer.abandonSession(sessionId);}catch(Exception ignored){}fail(callback,e);}
        },"update-install").start();
    }
}
