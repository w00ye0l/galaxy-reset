package com.forholiday.galaxyreset;

import java.net.URI;
import java.util.Set;

final class UpdatePolicy {
    static final String REPOSITORY="w00ye0l/galaxy-reset";
    static final String META_NAME="galaxy-reset-update.json", APK_NAME="galaxy-reset.apk";
    static final String LATEST_METADATA="https://github.com/"+REPOSITORY+"/releases/latest/download/"+META_NAME;
    static String apkForVersion(String versionName){
        if(versionName==null||!versionName.matches("[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new IllegalArgumentException("유효하지 않은 앱 버전입니다.");
        // Pin the APK to the metadata's release; latest may change during a check.
        return "https://github.com/"+REPOSITORY+"/releases/download/app-v"+versionName+"/"+APK_NAME;
    }
    static final long MAX_APK_BYTES=1024L*1024*1024;
    static boolean newer(long installed,long available){return available>installed;}
    static boolean validHash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    static boolean archiveMatches(String expectedPackage,long installed,long expected,String actualPackage,long actual){
        return expectedPackage.equals(actualPackage)&&actual==expected&&newer(installed,actual);
    }
    static boolean sameSigners(Set<String> installed,Set<String> incoming){return !installed.isEmpty()&&installed.equals(incoming);}
    static boolean releaseAsset(String url,String name){
        try{
            URI uri=URI.create(url);String path=uri.getPath();
            return "https".equals(uri.getScheme())&&"github.com".equals(uri.getHost())&&uri.getUserInfo()==null&&uri.getPort()==-1
                &&path.startsWith("/"+REPOSITORY+"/releases/download/")&&path.endsWith("/"+name)
                &&!path.contains("/../")&&!path.contains("/./");
        }catch(Exception ignored){return false;}
    }
    static boolean downloadHost(URI uri){
        return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&uri.getPort()==-1&&uri.getHost()!=null
            &&Set.of("github.com","release-assets.githubusercontent.com","objects.githubusercontent.com").contains(uri.getHost());
    }
}
