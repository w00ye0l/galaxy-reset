package com.forholiday.galaxyreset.fixture;
import android.app.Service;
import android.accounts.*;
import android.content.*;
import android.os.*;
public class AuthenticatorService extends Service {
    @Override public IBinder onBind(Intent intent) { return new Auth(this).getIBinder(); }
    static class Auth extends AbstractAccountAuthenticator {
        Auth(Context context) { super(context); }
        Bundle no() { return new Bundle(); }
        public Bundle editProperties(AccountAuthenticatorResponse r,String type){return no();}
        public Bundle addAccount(AccountAuthenticatorResponse r,String type,String token,String[] features,Bundle options){return no();}
        public Bundle confirmCredentials(AccountAuthenticatorResponse r,Account a,Bundle options){return no();}
        public Bundle getAuthToken(AccountAuthenticatorResponse r,Account a,String token,Bundle options){return no();}
        public String getAuthTokenLabel(String token){return "validation-only";}
        public Bundle updateCredentials(AccountAuthenticatorResponse r,Account a,String token,Bundle options){return no();}
        public Bundle hasFeatures(AccountAuthenticatorResponse r,Account a,String[] features){return no();}
        @Override public Bundle getAccountRemovalAllowed(AccountAuthenticatorResponse r,Account a){Bundle result=new Bundle();result.putBoolean(AccountManager.KEY_BOOLEAN_RESULT,true);return result;}
    }
}
