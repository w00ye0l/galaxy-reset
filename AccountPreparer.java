import java.io.*;
import android.content.*;
import android.content.pm.*;
import android.content.res.XmlResourceParser;
import android.os.*;
import com.forholiday.galaxyreset.AccountPreparationPolicy;
import com.forholiday.galaxyreset.AccountPreparationPolicy.Provider;
import java.util.*;
import org.xmlpull.v1.XmlPullParser;

/** Shell-only preparation: read signed authenticator metadata, never user credentials. */
public final class AccountPreparer {
    static final String NS="http://schemas.android.com/apk/res/android";
    public static void main(String[] args) {
        try {
            if(android.os.Process.myUid()!=2000)throw new SecurityException("Shell required");
            boolean list=args.length==1&&args[0].equals("--list");
            boolean prepare=args.length==1&&args[0].equals("--prepare");
            if(!list&&!prepare)throw new IllegalArgumentException("Unknown mode");
            Looper.prepareMainLooper();
            Class<?> thread=Class.forName("android.app.ActivityThread");
            Context context=(Context)thread.getMethod("getSystemContext").invoke(thread.getMethod("systemMain").invoke(null));
            PackageManager pm=context.getPackageManager();
            int flags=PackageManager.GET_META_DATA|PackageManager.MATCH_DISABLED_COMPONENTS|PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
            List<Provider> providers=new ArrayList<>();
            for(ResolveInfo resolved:pm.queryIntentServices(new Intent("android.accounts.AccountAuthenticator"),flags)) {
                ServiceInfo service=resolved.serviceInfo;
                if(service==null)continue;
                try(XmlResourceParser xml=service.loadXmlMetaData(pm,"android.accounts.AccountAuthenticator")) {
                    if(xml==null)continue;
                    int event;while((event=xml.next())!=XmlPullParser.END_DOCUMENT&&event!=XmlPullParser.START_TAG){}
                    if(!xml.getName().equals("account-authenticator"))continue;
                    int resource=xml.getAttributeResourceValue(NS,"accountType",0);
                    String type=resource==0?xml.getAttributeValue(NS,"accountType"):pm.getResourcesForApplication(service.applicationInfo).getString(resource);
                    if(type==null||type.isEmpty())throw new IllegalStateException("Missing account type");
                    providers.add(new Provider(type,service.packageName,service.name));
                    if(list)System.out.println("AUTH:"+type+":"+service.packageName+":"+service.name+":"+service.applicationInfo.enabled+":"+service.enabled);
                }
            }
            if(prepare) {
                java.lang.Process dump=new ProcessBuilder("/system/bin/dumpsys","account").redirectErrorStream(true).start();
                ByteArrayOutputStream output=new ByteArrayOutputStream();
                try(InputStream in=dump.getInputStream()) {
                    byte[] chunk=new byte[8192];int count;
                    while((count=in.read(chunk))!=-1) {
                        if(output.size()+count>4*1024*1024)throw new IOException("Account inventory too large");
                        output.write(chunk,0,count);
                    }
                }
                if(dump.waitFor()!=0)throw new IOException("Account inventory failed");
                Set<String> types=AccountPreparationPolicy.accountTypes(output.toString("UTF-8"));
                for(Provider p:AccountPreparationPolicy.select(types,providers)) {
                    pm.setApplicationEnabledSetting(p.pkg,PackageManager.COMPONENT_ENABLED_STATE_ENABLED,0);
                    ComponentName component=new ComponentName(p.pkg,p.service);
                    // Shell may enable a whole disabled app, but Android restricts changing
                    // individual components. Verify the provider instead of hiding that failure.
                    if(!pm.getApplicationInfo(p.pkg,0).enabled||!pm.getServiceInfo(component,0).enabled)throw new IllegalStateException("Enable verification failed");
                    System.out.println("PREPARED:"+p.pkg);
                }
            }
            System.out.println("SUCCESS:AUTHENTICATORS_READY");System.exit(0);
        } catch(Exception e) {
            // No account names, XML dumps, or tokens in logs.
            System.out.println("FAIL:AUTHENTICATOR_PREPARATION:"+e.getClass().getSimpleName());System.exit(1);
        }
    }
}
