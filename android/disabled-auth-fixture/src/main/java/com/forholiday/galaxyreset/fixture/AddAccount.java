package com.forholiday.galaxyreset.fixture;
import android.app.Activity;
import android.accounts.*;
import android.os.*;
public class AddAccount extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        new Handler(Looper.getMainLooper()).postDelayed(()->{
            getPackageManager().setComponentEnabledSetting(new android.content.ComponentName(this,AuthenticatorService.class),android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,android.content.pm.PackageManager.DONT_KILL_APP);
            AccountManager manager=AccountManager.get(this);
            Account account=new Account("validation-only","com.forholiday.galaxyreset.fixture");
            if(getIntent().getBooleanExtra("cleanup",false))manager.removeAccountExplicitly(account);
            else {manager.addAccountExplicitly(account,null,null);manager.setAccountVisibility(account,"com.android.shell",AccountManager.VISIBILITY_VISIBLE);}
            if(getIntent().getBooleanExtra("disable-service",false))getPackageManager().setComponentEnabledSetting(new android.content.ComponentName(this,AuthenticatorService.class),android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,android.content.pm.PackageManager.DONT_KILL_APP);
            finish();
        },600);
    }
}
