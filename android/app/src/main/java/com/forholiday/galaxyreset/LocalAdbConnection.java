package com.forholiday.galaxyreset;

import android.content.Context;
import android.os.Build;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;
import io.github.muntashirakon.adb.AbsAdbConnectionManager;

final class LocalAdbConnection extends AbsAdbConnectionManager {
    private final AdbCredentials credentials;
    LocalAdbConnection(Context context) throws Exception {
        credentials=AdbCredentials.load(context);
        setApi(Build.VERSION.SDK_INT);setHostAddress("127.0.0.1");setTimeout(10,TimeUnit.SECONDS);setThrowOnUnauthorised(true);
    }
    @Override protected PrivateKey getPrivateKey(){return credentials.key;}
    @Override protected Certificate getCertificate(){return credentials.certificate;}
    @Override protected String getDeviceName(){return "GalaxyReset";}
}
