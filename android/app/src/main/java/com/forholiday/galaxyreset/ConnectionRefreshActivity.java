package com.forholiday.galaxyreset;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/** No commands or reset actions. Shizuku observes this short-lived foreground process. */
public final class ConnectionRefreshActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing()) finish();
        }, 750);
    }
}
