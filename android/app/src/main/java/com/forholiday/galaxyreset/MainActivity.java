package com.forholiday.galaxyreset;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.*;
import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int BG=0xfff0f5f3, WHITE=0xffffffff, INK=0xff151d1b, MUTED=0xff687570,
        TEAL=0xff007c70, PALE=0xffdff2ec, BORDER=0xffd8e1de, RED=0xffcf2926, AMBER=0xff96631e;
    private static final String[] NAMES={"日本語 (일본어)","English (영어)","한국어","中文简体 (중국어 간체)","中文繁體 (중국어 번체)"};
    private static final String[] SCREENS={"연결","시작","삭제 확인","진행 중","완료","일부 실패"};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private RunState state;
    private LinearLayout frame, body, footer, rows;
    private ScrollView contentScroll, records;
    private TextView percent, progressLabel, countLabel;
    private ProgressBar progressBar;
    private int preview=-1, override=-1;
    private String selected="ja-JP", signature="";
    private Bitmap wallpaper;
    private Dialog activeDialog;
    private long backPressedAt;
    private boolean shuttingDown;
    private final Shizuku.OnBinderReceivedListener received=()->{ShellConnection.INSTANCE.connect(false); refresh();};
    private final Shizuku.OnBinderDeadListener dead=()->{ShellConnection.INSTANCE.lost(); refresh();};
    private final Shizuku.OnRequestPermissionResultListener permission=(request,result)->{ShellConnection.INSTANCE.connect(false); refresh();};
    private final Runnable tick=new Runnable(){public void run(){refresh();handler.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); state=new RunState(this); state.detectInterruption();
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
        selected=saved==null?state.locale():saved.getString("selected",state.locale());
        if(saved!=null){override=saved.getInt("override",-1);}
        if(state.homeOnNextLaunch()&&!running()){preview=-1;override=1;}
        try { wallpaper=BitmapFactory.decodeStream(getAssets().open(ResetPipeline.series(Build.MODEL)+".png")); } catch(Exception ignored) {}
        Shizuku.addBinderReceivedListenerSticky(received); Shizuku.addBinderDeadListener(dead); Shizuku.addRequestPermissionResultListener(permission);
        render();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},20);
    }
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("selected",selected);out.putInt("preview",preview);out.putInt("override",override);}
    int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    boolean running(){return ResetService.alive||state.phase().equals("running");}
    boolean demo(){return preview>=0;}
    int screen(){
        if(demo())return preview;
        if(running())return 3;
        if(override>=0)return override;
        return switch(state.phase()){case "done"->4;case "issues","interrupted"->5;default->ShellConnection.INSTANCE.ready()?1:0;};
    }
    String deviceName(){String name=Settings.Global.getString(getContentResolver(),"device_name");return name==null?Build.MODEL:name;}
    String ko(String locale){return switch(locale){case "ja-JP"->"일본어";case "en-US"->"영어";case "zh-CN"->"중국어 간체";case "zh-TW"->"중국어 번체";default->"한국어";};}
    String languageText(String tag){List<String> extra=new ArrayList<>();for(String locale:ResetPipeline.locales(tag).subList(1,ResetPipeline.locales(tag).size()))extra.add(ko(locale));return ko(tag)+" (보조: "+String.join(", ",extra)+")";}
    LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    LinearLayout row(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.HORIZONTAL);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    TextView label(String value,int size,int color,boolean bold){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setFontFeatureSettings("tnum");if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return view;}
    TextView text(LinearLayout parent,String value,int size,int color,boolean bold){TextView view=label(value,size,color,bold);parent.addView(view);return view;}
    void space(LinearLayout parent,int height){View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(height)));}
    GradientDrawable shape(int color,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    Drawable ripple(int color,int radius,int stroke){return new RippleDrawable(ColorStateList.valueOf(0x18007c70),shape(color,radius,stroke),shape(WHITE,radius,0));}
    void card(LinearLayout view,int color){view.setBackground(shape(color,18,0));view.setPadding(dp(18),dp(18),dp(18),dp(18));}
    ImageView icon(int kind,int color,int size){ImageView image=new ImageView(this);image.setImageDrawable(UiIcon.create(this,kind,color,dp(size)));image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);image.setLayoutParams(new LinearLayout.LayoutParams(dp(size),dp(size)));return image;}
    Button button(String title,int kind,int fill,int color,Runnable action){
        Button button=new Button(this);button.setText(title);button.setAllCaps(false);button.setTextSize(15);button.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        button.setStateListAnimator(null);button.setElevation(0);button.setTextColor(color);button.setBackground(ripple(fill,13,fill==WHITE?BORDER:0));button.setMinHeight(0);button.setMinimumHeight(0);
        button.setPadding(dp(18),0,dp(18),0);button.setCompoundDrawablePadding(dp(8));button.setCompoundDrawables(UiIcon.create(this,kind,color,dp(20)),null,null,null);
        button.setOnClickListener(v->action.run());return button;
    }
    Button action(LinearLayout parent,String title,int kind,int fill,int color,Runnable callback){Button b=button(title,kind,fill,color,callback);parent.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));return b;}
    void divider(LinearLayout parent){View line=new View(this);line.setBackgroundColor(BORDER);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(1));p.setMargins(0,dp(14),0,dp(14));parent.addView(line,p);}
    ImageView wallpaperImage(int width,int height){ImageView image=new ImageView(this);image.setImageBitmap(wallpaper);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setBackground(shape(WHITE,5,BORDER));image.setClipToOutline(true);image.setContentDescription(ResetPipeline.series(Build.MODEL)+" 배경화면 미리보기");image.setLayoutParams(new LinearLayout.LayoutParams(dp(width),dp(height)));return image;}
    void refresh(){
        if(state==null)return;state.detectInterruption();
        StringBuilder key=new StringBuilder(screen()+"|"+preview+"|"+ShellConnection.INSTANCE.ready()+"|"+selected+"|"+state.phase()+"|"+ShellConnection.INSTANCE.message);
        for(int i=0;i<19;i++)key.append('|').append(state.status(i)).append(state.detail(i));
        key.append(state.prefs.getString("summary",""));
        if(!key.toString().equals(signature)){signature=key.toString();
            if(screen()==3&&frame!=null&&frame.getTag()!=null&&frame.getTag().equals("progress:"+preview))updateProgress();else render();
        }
    }
    void render(){
        int current=screen();frame=column();frame.setBackgroundColor(BG);frame.setTag(current==3?"progress:"+preview:"screen:"+current);setContentView(frame);
        frame.setOnApplyWindowInsetsListener((view,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});frame.requestApplyInsets();
        WindowInsetsController controls=frame.getWindowInsetsController();if(controls!=null)controls.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        contentScroll=new ScrollView(this);contentScroll.setFillViewport(true);body=column();body.setPadding(dp(20),dp(8),dp(20),dp(20));contentScroll.addView(body);frame.addView(contentScroll,new LinearLayout.LayoutParams(-1,0,1));
        footer=column();footer.setPadding(dp(20),dp(12),dp(20),dp(16));footer.setBackgroundColor(WHITE);frame.addView(footer);
        switch(current){case 0->connectionScreen();case 1,2->startScreen();case 3->progressScreen();case 4->doneScreen();case 5->failureScreen();}
        if(current==2)handler.post(()->confirmRun(0,false,true));
    }
    void connectionScreen(){
        space(body,18);text(body,"권한 연결 확인",28,INK,true);space(body,10);text(body,"Shizuku가 실행 중이면 자동으로 연결합니다.",14,MUTED,false);space(body,24);
        LinearLayout steps=column();card(steps,WHITE);body.addView(steps);
        boolean wifi=false;try{ConnectivityManager cm=getSystemService(ConnectivityManager.class);NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());wifi=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);}catch(Exception ignored){}
        boolean ready=ShellConnection.INSTANCE.ready();
        connectionRow(steps,"Wi-Fi 연결","현장 Wi-Fi에 연결해주세요",UiIcon.WIFI,demo()||wifi,()->open(new Intent(Settings.ACTION_WIFI_SETTINGS)));divider(steps);
        connectionRow(steps,"무선 디버깅","개발자 옵션에서 켜고 페어링해주세요",UiIcon.PHONE,demo()||Settings.Global.getInt(getContentResolver(),"adb_wifi_enabled",0)==1,this::debugGuide);divider(steps);
        connectionRow(steps,"Shizuku 시작","무선 디버깅으로 Shizuku를 시작해주세요",UiIcon.REFRESH,demo()||Shizuku.pingBinder(),this::openShizuku);divider(steps);
        connectionRow(steps,"초기화 권한","초기화 앱의 Shizuku 권한을 허용해주세요",UiIcon.SHIELD,demo()||ready,this::connectShizuku);
        space(body,18);text(body,demo()?"연결 상태를 보여주는 예시 화면입니다.":ShellConnection.INSTANCE.message,13,MUTED,false);
        space(body,12);text(body,"최초 페어링과 재부팅 후 시작은 Shizuku에서 진행합니다. 각 항목을 눌러 설정을 열 수 있어요.",13,MUTED,false);
        Button next=action(footer,"계속",UiIcon.ARROW,TEAL,WHITE,()->{if(demo()){preview=1;render();}else{override=1;render();}});next.setEnabled(demo()||ready);
        space(footer,8);action(footer,"연결하기",UiIcon.REFRESH,WHITE,INK,()->{if(!demo())connectShizuku();else{preview=1;render();}});
    }
    void connectionRow(LinearLayout parent,String title,String subtitle,int kind,boolean good,Runnable callback){LinearLayout line=row();line.addView(icon(kind,TEAL,24));LinearLayout copy=column();LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(12),0,dp(8),0);line.addView(copy,p);text(copy,title,15,INK,true);space(copy,4);text(copy,subtitle,12,MUTED,false);line.addView(icon(good?UiIcon.CHECK:UiIcon.ARROW,good?TEAL:MUTED,20));line.setMinimumHeight(dp(52));line.setOnClickListener(v->{if(!demo())callback.run();});parent.addView(line);}
    void startScreen(){
        LinearLayout device=row();card(device,WHITE);LinearLayout info=column();device.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        text(info,"이 기기",12,MUTED,true);space(info,6);text(info,deviceName(),28,INK,true);space(info,4);text(info,Build.MODEL+" · "+ResetPipeline.series(Build.MODEL)+" 시리즈",12,MUTED,false);space(info,10);
        LinearLayout chips=row();chip(chips,"Wi-Fi",UiIcon.WIFI);chip(chips,"무선 디버깅",UiIcon.CHECK);info.addView(chips);space(info,5);LinearLayout auth=row();chip(auth,ShellConnection.INSTANCE.ready()||demo()?"권한 연결됨":"연결 확인 필요",UiIcon.SHIELD);info.addView(auth);
        LinearLayout image=column();image.setGravity(Gravity.CENTER);image.addView(wallpaperImage(58,112));space(image,5);text(image,"적용할 배경",10,MUTED,false);device.addView(image);body.addView(device);
        space(body,20);LinearLayout heading=row();heading.addView(label("손님 언어",18,INK,true),new LinearLayout.LayoutParams(0,-2,1));heading.addView(label("하나를 고르세요",12,MUTED,false));body.addView(heading);space(body,10);
        for(int i=0;i<5;i++){final String tag=ResetPipeline.LOCALES[i];boolean chosen=tag.equals(selected);LinearLayout option=row();option.setPadding(dp(12),dp(4),dp(14),dp(4));option.setBackground(ripple(chosen?PALE:WHITE,12,chosen?TEAL:BORDER));
            RadioButton radio=new RadioButton(this);radio.setChecked(chosen);radio.setButtonTintList(ColorStateList.valueOf(chosen?TEAL:MUTED));radio.setClickable(false);radio.setFocusable(false);radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);option.addView(radio,new LinearLayout.LayoutParams(dp(32),dp(40)));
            option.addView(label(NAMES[i],14,INK,true),new LinearLayout.LayoutParams(0,-2,1));option.addView(label(tag,11,MUTED,false));option.setContentDescription(NAMES[i]+(chosen?", 선택됨":""));option.setOnClickListener(v->{selected=tag;render();});body.addView(option,new LinearLayout.LayoutParams(-1,dp(49)));space(body,7);
        }
        space(body,4);text(body,"기기 언어: "+languageText(selected),12,INK,false);
        if(state.completed()>0){space(body,12);TextView history=text(body,"지난 초기화 결과 보기",12,TEAL,true);history.setPadding(0,dp(8),0,dp(8));history.setOnClickListener(v->{override=state.phase().equals("done")?4:5;render();});}
        text(footer,"직원 전용 · 고객님은 실행하지 마세요",12,RED,true);space(footer,6);
        TextView hint=text(footer,"19단계 진행 · 끝날 때까지 화면을 만지지 마세요",11,MUTED,false);hint.setCompoundDrawables(UiIcon.create(this,UiIcon.INFO,MUTED,dp(14)),null,null,null);hint.setCompoundDrawablePadding(dp(6));space(footer,10);
        Button start=action(footer,"초기화 시작",UiIcon.REFRESH,TEAL,WHITE,()->confirmRun(0,false,true));start.setEnabled(demo()||ShellConnection.INSTANCE.ready());
        if(!demo()&&!ShellConnection.INSTANCE.ready()){space(footer,8);action(footer,"Shizuku 연결하기",UiIcon.SHIELD,WHITE,INK,this::connectShizuku);}
    }
    void chip(LinearLayout parent,String title,int kind){TextView chip=label(title,10,TEAL,true);chip.setPadding(dp(7),dp(5),dp(7),dp(5));chip.setCompoundDrawables(UiIcon.create(this,kind,TEAL,dp(12)),null,null,null);chip.setCompoundDrawablePadding(dp(4));chip.setBackground(shape(PALE,20,0));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,-2);p.setMargins(0,0,dp(5),0);parent.addView(chip,p);}
    void progressScreen(){
        LinearLayout top=column();card(top,WHITE);LinearLayout heading=row();percent=label("",42,INK,true);heading.addView(percent,new LinearLayout.LayoutParams(0,-2,1));countLabel=label("",13,MUTED,true);heading.addView(countLabel);top.addView(heading);space(top,12);
        progressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progressBar.setMax(19);progressBar.setProgressTintList(ColorStateList.valueOf(TEAL));progressBar.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));top.addView(progressBar,new LinearLayout.LayoutParams(-1,dp(8)));space(top,12);progressLabel=text(top,"",15,TEAL,true);body.addView(top);space(body,12);
        LinearLayout tip=row();tip.setPadding(dp(12),dp(12),dp(12),dp(12));tip.setBackground(shape(0xfffff1dc,12,0));tip.addView(icon(UiIcon.ALERT,AMBER,20));TextView message=label("화면이 깜빡이거나 홈 화면으로 나가도 정상이에요. 끝나면 돌아옵니다. 충전기를 연결해 두세요.",12,AMBER,false);LinearLayout.LayoutParams m=new LinearLayout.LayoutParams(0,-2,1);m.setMargins(dp(8),0,0,0);tip.addView(message,m);body.addView(tip);space(body,16);
        LinearLayout title=row();title.addView(label("진행 기록",14,INK,true),new LinearLayout.LayoutParams(0,-2,1));title.addView(label("눌러서 상세 확인",11,MUTED,false));body.addView(title);space(body,8);
        // One scroll owner: clickable step rows must not compete with a nested ScrollView.
        records=contentScroll;rows=column();rows.setBackground(shape(WHITE,18,0));rows.setPadding(dp(14),dp(10),dp(14),dp(10));body.addView(rows);
        if(demo())action(footer,"완료 화면 보기",UiIcon.ARROW,TEAL,WHITE,()->{preview=4;render();});
        else if(!running())action(footer,"결과 화면으로",UiIcon.ARROW,TEAL,WHITE,()->{override=-1;render();});
        else {text(footer,deviceName()+" · "+ko(state.locale()),12,MUTED,false);footer.setBackgroundColor(BG);}
        updateProgress();
    }
    String displayedStatus(int i){if(!demo())return state.status(i);return i<6?"완료":i==6?"진행 중":"대기";}
    void updateProgress(){if(rows==null||percent==null)return;int count=demo()?6:state.completed();percent.setText((count*100/19)+"%");countLabel.setText(count+" / 19");progressBar.setProgress(count);progressLabel.setText(demo()?"로그·캐시 정리 중…":running()?ResetPipeline.LABELS[state.index()]+" 중…":"전체 단계 기록");
        int scrollY=records.getScrollY();rows.removeAllViews();for(int i=0;i<19;i++){final int step=i;String status=displayedStatus(i);boolean done=status.equals("완료"),active=status.equals("진행 중"),problem=status.equals("실패")||status.equals("수동 확인");LinearLayout item=row();item.setPadding(dp(4),dp(10),dp(4),dp(10));item.setMinimumHeight(dp(48));item.setBackground(ripple(active?PALE:WHITE,10,0));item.addView(icon(done?UiIcon.CHECK:problem?UiIcon.ALERT:active?UiIcon.REFRESH:UiIcon.INFO,done||active?TEAL:problem?AMBER:BORDER,20));LinearLayout copy=column();LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(10),0,dp(6),0);item.addView(copy,p);text(copy,ResetPipeline.LABELS[i],13,active?TEAL:status.equals("대기")?MUTED:INK,true);
            if(problem){space(copy,3);text(copy,status,11,AMBER,false);}item.addView(label(String.format(Locale.ROOT,"%02d",i+1),11,MUTED,false));item.setContentDescription((i+1)+"단계 "+ResetPipeline.LABELS[i]+", "+status+", 상세 확인");item.setOnClickListener(v->showStep(step));rows.addView(item);}
        records.post(()->{if(running()&&!demo())records.smoothScrollTo(0,rows.getTop()+rows.getChildAt(state.index()).getTop());else records.scrollTo(0,scrollY);});
    }
    void hero(String title,String subtitle,int kind,int fill){space(body,22);ImageView symbol=icon(kind,WHITE,42);symbol.setPadding(dp(15),dp(15),dp(15),dp(15));symbol.setBackground(shape(fill,40,0));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(76),dp(76));p.gravity=Gravity.CENTER_HORIZONTAL;body.addView(symbol,p);space(body,18);TextView name=text(body,title,26,INK,true);name.setGravity(Gravity.CENTER);space(body,10);TextView details=text(body,subtitle,12,MUTED,false);details.setGravity(Gravity.CENTER);space(body,24);}
    void doneScreen(){hero("초기화 완료",deviceName()+" · "+(demo()?19:state.completed())+" / 19단계",UiIcon.CHECK,TEAL);LinearLayout results=column();card(results,WHITE);String summary=state.prefs.getString("summary","");
        resultRow(results,"남은 계정",demo()?"삼성 계정 보존 · 비삼성 계정 0개":summary.contains("남은 비삼성 계정: 0개")?"비삼성 계정 0개 · 삼성 계정 보존":"계정 확인 결과를 상세 기록에서 확인",false);divider(results);
        resultRow(results,"언어",languageText(demo()?selected:state.locale()),false);divider(results);
        resultRow(results,"필수 앱",demo()||!summary.contains(": 없음")?"네이버 지도 · File Manager · 갤럭시 초기화":"필수 앱 설치 결과 확인 필요",false);divider(results);
        resultRow(results,"배경화면",ResetPipeline.series(Build.MODEL)+" 기본 배경 · 단계 처리 완료",true);body.addView(results);space(body,16);
        action(body,"19단계 결과 상세 보기",UiIcon.INFO,WHITE,INK,()->{if(demo())preview=3;else override=3;render();});
        LinearLayout actions=row();
        Button home=button("시작 화면으로",UiIcon.HOME,WHITE,INK,()->{goHome();render();});
        Button shutdown=button("전원 끄기",UiIcon.POWER,INK,WHITE,this::confirmShutdown);
        home.setTextSize(14);shutdown.setTextSize(14);
        actions.addView(home,new LinearLayout.LayoutParams(0,dp(52),1));
        LinearLayout.LayoutParams shutdownLayout=new LinearLayout.LayoutParams(0,dp(52),1);
        shutdownLayout.setMargins(dp(10),0,0,0);actions.addView(shutdown,shutdownLayout);footer.addView(actions);
    }
    void resultRow(LinearLayout parent,String title,String value,boolean image){LinearLayout row=row();LinearLayout copy=column();row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));text(copy,title,11,MUTED,false);space(copy,5);text(copy,value,14,INK,true);if(image)row.addView(wallpaperImage(35,64));else row.addView(icon(UiIcon.CHECK,TEAL,20));parent.addView(row);}
    void failureScreen(){int failed=0,manual=0;for(int i=0;i<19;i++){if(state.status(i).equals("실패"))failed++;if(state.status(i).equals("수동 확인"))manual++;}hero("확인이 필요해요",deviceName()+" · 실패 "+(demo()?1:failed)+" · 수동 확인 "+(demo()?1:manual),UiIcon.ALERT,AMBER);
        if(demo()){issueCard(1,"실패","비삼성 계정이 남아 있는 경우의 예시입니다.");space(body,12);issueCard(2,"수동 확인","자동 삭제가 거부되면 SIM 관리자에서 e-SIM을 확인합니다.");}
        else {if(state.phase().equals("interrupted")){LinearLayout alert=column();card(alert,WHITE);text(alert,"실행이 중단됐어요",16,INK,true);space(alert,8);text(alert,state.prefs.getString("message","연결을 확인해주세요."),13,MUTED,false);space(alert,12);Button resume=action(alert,"여기서부터 다시 시도",UiIcon.REFRESH,TEAL,WHITE,()->confirmRun(state.index(),false,false));resume.setEnabled(ShellConnection.INSTANCE.ready());body.addView(alert);space(body,12);}
            for(int i=0;i<19;i++)if(state.status(i).equals("실패")||state.status(i).equals("수동 확인")){issueCard(i,state.status(i),state.detail(i));space(body,12);}
            if(!state.pending().isEmpty())text(body,"재연결 후 복구할 앱: "+String.join(", ",state.pending()),12,AMBER,false);
        }
        action(footer,"전체 단계 기록 보기",UiIcon.INFO,WHITE,INK,()->{if(demo())preview=3;else override=3;render();});space(footer,8);action(footer,"시작 화면으로",UiIcon.HOME,WHITE,INK,()->{if(demo())preview=1;else goHome();render();});
    }
    void issueCard(int index,String status,String detail){LinearLayout issue=column();card(issue,WHITE);text(issue,status+" · "+ResetPipeline.LABELS[index],16,INK,true);space(issue,10);text(issue,detail.isEmpty()?"단계 기록을 눌러 상세 결과를 확인하세요.":detail,13,MUTED,false);space(issue,14);Button retry=action(issue,"이 단계 다시 시도",UiIcon.REFRESH,WHITE,INK,()->{if(demo()){preview=3;render();}else confirmRun(index,true,false);});retry.setEnabled(demo()||ShellConnection.INSTANCE.ready());if(index==2&&status.equals("수동 확인")){space(issue,8);action(issue,"SIM 관리자 열기",UiIcon.PHONE,WHITE,INK,()->{if(demo())previewNotice("SIM 관리자");else open(new Intent("android.telephony.euicc.action.MANAGE_EMBEDDED_SUBSCRIPTIONS"));});}body.addView(issue);}
    void showStep(int index){LinearLayout content=column();card(content,WHITE);text(content,String.format(Locale.ROOT,"%02d",index+1)+" · "+ResetPipeline.LABELS[index],20,INK,true);space(content,10);text(content,"상태: "+displayedStatus(index)+(demo()?" (미리보기)":""),13,TEAL,true);space(content,18);text(content,"작업 내용",12,MUTED,true);space(content,6);text(content,StepDetails.DESCRIPTIONS[index],14,INK,false);space(content,16);text(content,"마지막 결과",12,MUTED,true);space(content,6);String detail=demo()?"화면 예시입니다. 실제 작업은 실행되지 않습니다.":state.detail(index);text(content,detail.isEmpty()?state.status(index).equals("완료")?"단계가 완료됐으며 기록된 오류가 없습니다.":"아직 상세 결과가 기록되지 않았습니다.":detail,13,MUTED,false);space(content,20);action(content,"닫기",UiIcon.CLOSE,TEAL,WHITE,this::dismissDialog);showDialog(content);}
    void showDialog(LinearLayout content){dismissDialog();activeDialog=new Dialog(this);ScrollView scroll=new ScrollView(this);scroll.addView(content);activeDialog.setContentView(scroll);Window window=activeDialog.getWindow();if(window!=null){window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setLayout(getResources().getDisplayMetrics().widthPixels-dp(40),-2);}activeDialog.show();if(window!=null)window.setLayout(getResources().getDisplayMetrics().widthPixels-dp(40),-2);}
    void dismissDialog(){if(activeDialog!=null){activeDialog.dismiss();activeDialog=null;}}
    void confirmRun(int from,boolean single,boolean fresh){
        if(!demo()&&(running()||!ShellConnection.INSTANCE.ready()))return;
        LinearLayout content=column();card(content,WHITE);
        LinearLayout warning=column();card(warning,0xffffe8e6);warning.addView(icon(UiIcon.ALERT,RED,30));space(warning,10);
        text(warning,"직원 전용 · 고객 실행 금지",21,RED,true);space(warning,8);
        text(warning,"고객님은 이 작업을 실행하지 마세요.\n대여 중인 기기에서는 실행하면 안 됩니다.",15,RED,true);
        content.addView(warning);space(content,16);
        text(content,single?"선택한 초기화 단계를 다시 실행합니다.":"이 기기의 사용자 데이터를 삭제합니다.",17,INK,true);space(content,8);
        text(content,"사진·동영상·파일·설치한 앱·비삼성 계정·e-SIM 등이 삭제될 수 있으며, 이 앱으로 되돌릴 수 없습니다.",14,RED,true);space(content,12);
        text(content,"대상 기기: "+deviceName()+" · "+Build.MODEL,13,INK,true);space(content,6);
        text(content,single?"실행 단계: "+ResetPipeline.LABELS[from]:fresh?"전체 19단계 · 삼성 계정·초기화 앱·Shizuku·필수 앱 보존":(from+1)+"단계부터 다시 실행",12,MUTED,false);space(content,14);
        CheckBox staff=new CheckBox(this);staff.setText("직원이며, 반납된 기기와 삭제 대상을 확인했습니다.");staff.setTextSize(14);staff.setTextColor(INK);staff.setButtonTintList(ColorStateList.valueOf(RED));content.addView(staff);space(content,10);
        text(content,"계속하려면 아래에 ‘초기화’를 직접 입력하세요.",13,INK,true);space(content,6);
        EditText phrase=new EditText(this);phrase.setHint("초기화");phrase.setSingleLine(true);phrase.setTextSize(16);
        phrase.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        phrase.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE|android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        phrase.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);phrase.setSaveEnabled(false);
        content.addView(phrase,new LinearLayout.LayoutParams(-1,dp(52)));space(content,16);
        LinearLayout actions=row();
        Button cancel=button("취소",UiIcon.CLOSE,WHITE,INK,()->{dismissDialog();if(preview==2){preview=1;render();}});
        Button erase=button(single?"이 단계 실행":"삭제 실행",UiIcon.DELETE,RED,WHITE,()->{
            if(!ResetConfirmation.allowed(staff.isChecked(),phrase.getText().toString()))return;
            if(!demo()&&(running()||!ShellConnection.INSTANCE.ready())){Toast.makeText(this,"Shizuku 연결 상태를 확인하세요.",Toast.LENGTH_SHORT).show();return;}
            dismissDialog();
            if(demo()){preview=3;render();return;}
            state.homeOnNextLaunch(false);override=-1;
            Intent intent=new Intent(this,ResetService.class).putExtra("from",from).putExtra("single",single).putExtra("fresh",fresh).putExtra("locale",selected);startForegroundService(intent);
        });
        Runnable update=()->{boolean allowed=ResetConfirmation.allowed(staff.isChecked(),phrase.getText().toString());erase.setEnabled(allowed);erase.setAlpha(allowed?1f:0.35f);};
        staff.setOnCheckedChangeListener((view,checked)->update.run());
        phrase.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence value,int start,int count,int after){}
            public void onTextChanged(CharSequence value,int start,int before,int count){update.run();}
            public void afterTextChanged(android.text.Editable value){}
        });update.run();
        actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(50),1));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(50),1);p.setMargins(dp(10),0,0,0);actions.addView(erase,p);content.addView(actions);
        content.setFocusableInTouchMode(true);content.requestFocus();showDialog(content);
        if(activeDialog.getWindow()!=null)activeDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }
    void previewNotice(String title){new AlertDialog.Builder(this).setTitle(title+" · 미리보기").setMessage("디자인 미리보기에서는 실제 명령을 실행하지 않습니다.").setPositiveButton("확인",null).show();}
    void confirmShutdown(){
        if(shuttingDown||running())return;
        new AlertDialog.Builder(this).setTitle("전원을 끌까요?")
            .setMessage((state.issues()?"확인할 항목이 남아있습니다.\n\n":"")+"초기화 앱의 진행·결과 기록, 완료 알림과 최근 앱 항목을 지운 뒤 전원을 끕니다. Shizuku 연결 권한은 유지됩니다.")
            .setNegativeButton("취소",null).setPositiveButton("전원 끄기",(d,w)->shutdown()).show();
    }
    void shutdown(){
        if(shuttingDown||running())return;shuttingDown=true;
        new Thread(()->{
            try{
                ShellConnection.INSTANCE.checkUid();
                handler.post(()->{
                    try{
                        if(running())throw new IllegalStateException("초기화 실행 중입니다.");
                        state.clearHistoryForShutdown();
                        getSystemService(NotificationManager.class).cancelAll();
                        closeApp();
                        new Thread(()->{
                            try{if(ShellConnection.INSTANCE.run("reboot -p",15).code()!=0)throw new IllegalStateException();}
                            catch(Exception e){shutdownFailed("전원 끄기 실패 · 기기 전원 버튼을 사용하세요.");}
                        },"shutdown-command").start();
                    }catch(Exception e){shutdownFailed(e.getMessage());}
                });
            }catch(Exception e){shutdownFailed("Shizuku 연결을 확인한 뒤 다시 시도하세요.");}
        },"shutdown-prepare").start();
    }
    void shutdownFailed(String message){handler.post(()->{shuttingDown=false;Toast.makeText(getApplicationContext(),message,Toast.LENGTH_LONG).show();});}
    void handleBack(){
        long now=SystemClock.elapsedRealtime();
        if(backPressedAt!=0&&now-backPressedAt<=2000){closeApp();return;}
        backPressedAt=now;
        Toast.makeText(this,running()?"한 번 더 누르면 앱이 닫힙니다. 초기화는 계속 진행됩니다.":"한 번 더 누르면 앱이 종료됩니다.",Toast.LENGTH_SHORT).show();
    }
    // Android 13+ uses OnBackInvokedDispatcher above; this override is only the Android 11/12 fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){if(Build.VERSION.SDK_INT<33)handleBack();}
    void closeApp(){dismissDialog();for(ActivityManager.AppTask task:getSystemService(ActivityManager.class).getAppTasks())task.finishAndRemoveTask();finishAndRemoveTask();}
    void debugGuide(){new AlertDialog.Builder(this).setTitle("무선 디버깅 연결").setMessage("Wi-Fi 연결 → 개발자 옵션의 무선 디버깅 켜기 → Shizuku에서 페어링·시작 → 이 앱의 권한 허용 순서로 진행하세요.\n\n재부팅 후에는 Shizuku를 다시 시작해야 합니다.").setPositiveButton("개발자 옵션 열기",(d,w)->open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))).setNegativeButton("닫기",null).show();}
    void goHome(){state.homeOnNextLaunch(true);override=1;}
    void connectShizuku(){if(Shizuku.pingBinder())ShellConnection.INSTANCE.connect(true);else openShizuku();}
    void openShizuku(){Intent launch=getPackageManager().getLaunchIntentForPackage(ResetPipeline.SHIZUKU);open(launch!=null?launch:new Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/download/")));}
    void open(Intent intent){try{startActivity(intent);}catch(Exception e){Toast.makeText(this,"설정에서 직접 열어주세요.",Toast.LENGTH_LONG).show();}}
    @Override protected void onResume(){super.onResume();handler.post(tick);ShellConnection.INSTANCE.connect(false);}
    @Override protected void onPause(){backPressedAt=0;handler.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){dismissDialog();Shizuku.removeBinderReceivedListener(received);Shizuku.removeBinderDeadListener(dead);Shizuku.removeRequestPermissionResultListener(permission);handler.removeCallbacks(tick);super.onDestroy();}
}
