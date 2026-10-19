package com.forholiday.galaxyreset;

import io.github.muntashirakon.adb.AdbAuthenticationFailedException;
import io.github.muntashirakon.adb.AdbPairingRequiredException;

final class WirelessPolicy {
    static String commandOutput(String response) throws java.io.IOException {
        String marker="\nGALAXY_ADB_EXIT:";int offset=response.lastIndexOf(marker);
        if(offset<0)throw new java.io.IOException("자동 연결 명령이 중단됐습니다. 다시 연결해주세요.");
        String code=response.substring(offset+marker.length()).trim();
        if(!code.equals("0"))throw new java.io.IOException("자동 연결 명령 실패 ("+code+")");
        return response.substring(0,offset).trim();
    }
    static boolean shouldStart(boolean paired,boolean running,boolean busy,long now,long retryAt,boolean force){
        return paired&&!running&&!busy&&(force||now>=retryAt);
    }
    static boolean authenticationFailure(Throwable error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof AdbPairingRequiredException||cause instanceof AdbAuthenticationFailedException)return true;
            String message=cause.getMessage();
            if(message!=null&&message.contains("CERTIFICATE_UNKNOWN"))return true;
        }
        return false;
    }
}
