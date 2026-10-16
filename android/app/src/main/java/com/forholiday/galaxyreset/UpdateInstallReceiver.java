package com.forholiday.galaxyreset;

import android.content.*;
import android.content.pm.PackageInstaller;

public class UpdateInstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            Intent confirmation=intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if(confirmation!=null){try{context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}catch(Exception ignored){}}
            try{context.getPackageManager().getPackageInstaller().abandonSession(intent.getIntExtra("session_id",-1));}catch(Exception ignored){}
            AppUpdater.INSTANCE.installPending=false;
            String message="설치 확인창을 열지 못했습니다. 앱에서 업데이트를 다시 시도하세요.";
            AppUpdater.INSTANCE.status=message;
            context.getSharedPreferences("updates",Context.MODE_PRIVATE).edit().putString("install_result",message).apply();
        }else{
            AppUpdater.INSTANCE.installPending=false;
            if(status==PackageInstaller.STATUS_SUCCESS)AppUpdater.apkFile(context).delete();
            String message=status==PackageInstaller.STATUS_SUCCESS?"앱 업데이트가 완료됐습니다.":status==PackageInstaller.STATUS_FAILURE_ABORTED?"앱 업데이트 설치가 취소됐습니다.":"앱 업데이트 설치 실패 · Android 설치 안내를 확인하세요.";
            context.getSharedPreferences("updates",Context.MODE_PRIVATE).edit().putString("install_result",message).apply();
            AppUpdater.INSTANCE.status=message;
        }
    }
}
