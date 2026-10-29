package com.forholiday.galaxyreset;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import java.lang.reflect.*;

/** Non-destructive geometry check. Uses preview views; never starts ResetService. */
public class ProgressLayoutCheck extends Instrumentation {
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    Object field(Object target,String name) throws Exception { Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(target); }
    void call(Object target,String method,Class<?>[] types,Object... args) throws Exception {
        Method m=MainActivity.class.getDeclaredMethod(method,types);m.setAccessible(true);m.invoke(target,args);
    }
    @Override public void onStart() {
        Bundle result=new Bundle(); Activity activity=null;
        try {
            activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final Activity target=activity;
            final Throwable[] error=new Throwable[1];
            runOnMainSync(()->{try {
                target.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                Field preview=MainActivity.class.getDeclaredField("preview");preview.setAccessible(true);preview.setInt(target,3);
                call(target,"render",new Class<?>[0]);
            } catch(Throwable e){error[0]=e;}});
            if(error[0]!=null)throw new Exception(error[0]);
            waitForIdleSync();
            int[] before=new int[2],after=new int[2];
            View header=(View)field(target,"percent");ScrollView records=(ScrollView)field(target,"records");
            if(field(target,"contentScroll")!=null)throw new AssertionError("Progress has an outer scroll owner");
            runOnMainSync(()->{header.getLocationOnScreen(before);try{call(target,"scrollRecordsToStep",new Class<?>[]{int.class},18);}catch(Exception e){error[0]=e;}});
            Thread.sleep(700);waitForIdleSync();
            runOnMainSync(()->header.getLocationOnScreen(after));
            if(error[0]!=null)throw new Exception(error[0]);
            if(before[1]!=after[1]||records.getScrollY()<=0||records.getHeight()<=0)throw new AssertionError("Header Y "+before[1]+" -> "+after[1]+", log Y="+records.getScrollY()+", height="+records.getHeight());
            runOnMainSync(()->{try{call(target,"showStep",new Class<?>[]{int.class},18);call(target,"dismissDialog",new Class<?>[0]);}catch(Exception e){error[0]=e;}});
            if(error[0]!=null)throw new Exception(error[0]);
            result.putString("result","PASS: fixed header, internal auto-scroll, detail dialog");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable e) {
            result.putString("failure",e.toString());finish(Activity.RESULT_CANCELED,result);
        }
    }
}
