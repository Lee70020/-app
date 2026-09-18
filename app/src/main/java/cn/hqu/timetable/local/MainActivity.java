package cn.hqu.timetable.local;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.json.*;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final String HOME = "https://jwapp.hqu.edu.cn/";
    // 0.2.5：service 直接指向课表页（wdkb），不再经过 portal。
    // 0.2.4 设备日志实测：portal/index.do?forceCas=1 页面在浏览器中加载完成后
    // 会立刻跳回 CAS（“反复安全验证”根源；requests 不执行 JS 所以桌面从未复现）。
    // 桌面已验证 CAS 接受该 service：登录 302 直达 wdkb 页（tools/output/test_wdkb_service.py）。
    static final String LOGIN_URL = "https://id.hqu.edu.cn/authserver/login?service=https%3A%2F%2Fjwapp.hqu.edu.cn%2Fjwapp%2Fsys%2Fwdkb%2F*default%2Findex.do";
    // 统一认证按 UA 返回两套不同页面：移动版把两个表单重复用同一个 id
    // (loginFromId)，导致页面自身的登录按钮和 ?service= 注入都会命中隐藏的
    // 动态码表单，密码表单既拿不到 service 也提交不了。这里固定用桌面 UA，
    // 取得唯一 id 的桌面版页面（#pwdFromId），该布局已端到端验证通过。
    static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    static final int BLUE=0xff225ae8, INK=0xff16213b, MUTED=0xff65718a, BG=0xfff4f6fb;
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Handler handler = new Handler(Looper.getMainLooper());
    LinearLayout root, home, list, browserPanel;
    TextView summary, empty, browserStatus, weekLabel;
    WebView web, homeWeb;
    boolean homeReady=false, openChanges=false;
    long renderedFileTime=-1;
    JSONObject document, creds;
    int week=1;
    boolean busy=false, pendingAutoLogin=false, pendingSync=false;
    int loginInjects=0, loginPolls=0, loginGen=0;
    boolean loggedOutFlag=false, initialLoadPending=false, wantSync=false;
    String lastLoginTrace="";
    final LoginFlow loginFlow=new LoginFlow();
    boolean waitingSession=false;
    int sessionWaitGen=0, httpUpgradeCount=0;
    long sessionWaitStarted=0;
    static final String COOKIE_URL="https://jwapp.hqu.edu.cn/jwapp/sys/wdkb/modules/xskcb/cxxljc.do";
    final String[] days={"未标明星期","星期一","星期二","星期三","星期四","星期五","星期六","星期日"};

    int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(5),0,dp(5));return t;}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    Button button(String label,boolean primary,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setTextColor(primary?Color.WHITE:BLUE);b.setBackground(shape(primary?BLUE:0xffe9efff,12));b.setMinHeight(dp(44));b.setPadding(dp(10),dp(8),dp(10),dp(8));b.setOnClickListener(v->action.run());return b;}
    void addButton(LinearLayout row,Button b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(46),1);p.setMargins(dp(3),dp(5),dp(3),dp(5));row.addView(b,p);}
    void space(LinearLayout v,int h){View s=new View(this);v.addView(s,new LinearLayout.LayoutParams(1,dp(h)));}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        trace("app 0.3.1 / login-flow v5");
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        root=column();root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);
        home=column();root.addView(home,new LinearLayout.LayoutParams(-1,-1));
        homeWeb=new WebView(this);homeWeb.setBackgroundColor(BG);
        homeWeb.getSettings().setJavaScriptEnabled(true);
        homeWeb.getSettings().setAllowFileAccess(false);homeWeb.getSettings().setAllowContentAccess(false);
        homeWeb.getSettings().setBlockNetworkLoads(true);
        homeWeb.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        homeWeb.addJavascriptInterface(new HomeBridge(),"App");
        homeWeb.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return true;}
            @Override public boolean shouldOverrideUrlLoading(WebView v,String url){return true;}
            @Override public void onPageFinished(WebView v,String url){homeReady=true;render();}
        });
        home.addView(homeWeb,new LinearLayout.LayoutParams(-1,-1));
        try{String html=readAsset("home.html").replace("/*TIMETABLE_SCRIPT*/",readAsset("timetable.js")).replace("/*HOME_SCRIPT*/",readAsset("home.js"));
            homeWeb.loadDataWithBaseURL("https://app.local/",html,"text/html","UTF-8",null);
        }catch(Exception e){alert("界面加载失败","请重新安装应用。");}
        loadCreds();loadLocal();openChanges=getIntent().getBooleanExtra("openChanges",false);
        SyncNotifications.channel(this);AutoSync.ensure(this);render();
        if(document!=null&&!AutoSync.prefs(this).getBoolean("permissionAsked",false))
            handler.postDelayed(()->notificationPermission(false),1200);
    }
    public class HomeBridge{
        @JavascriptInterface public void action(String action){runOnUiThread(()->homeAction(action));}
    }
    void homeAction(String action){
        switch(action){
            case "sync":autoFlow();break;
            case "account":showSettings(false);break;
            case "import":importFile();break;
            case "export":export();break;
            case "diagnostics":trace("background: "+AutoSync.prefs(this).getString("lastStep","尚未执行"));showTrace();break;
            case "notifications":notificationPermission(true);break;
            case "background":openSystemSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,true);break;
            case "clockSettings":openSystemSettings(android.provider.Settings.ACTION_DATE_SETTINGS,false);break;
            case "toggleAuto":AutoSync.configure(this,!AutoSync.enabled(this),AutoSync.prefs(this).getBoolean("daytime",true));render();break;
            case "toggleDay":AutoSync.configure(this,AutoSync.enabled(this),!AutoSync.prefs(this).getBoolean("daytime",true));render();break;
            case "readChanges":
                JSONArray history=document==null?null:document.optJSONArray("change_history");
                if(history!=null&&history.optJSONObject(0)!=null)AutoSync.prefs(this).edit().putString("seen",history.optJSONObject(0).optString("id")).apply();
                break;
        }
    }
    void openSystemSettings(String action,boolean packageUri){
        try{Intent i=new Intent(action);if(packageUri)i.setData(Uri.parse("package:"+getPackageName()));startActivity(i);}
        catch(Exception e){toast("请在手机设置中打开应用权限或后台运行设置。");}
    }
    void notificationPermission(boolean explicit){
        if(isFinishing()||isDestroyed())return;
        boolean asked=AutoSync.prefs(this).getBoolean("permissionAsked",false);
        AutoSync.prefs(this).edit().putBoolean("permissionAsked",true).apply();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED&&(!asked||shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS))){
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},30);
        }else if(explicit){
            try{Intent i=new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS);i.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName());startActivity(i);}
            catch(Exception e){openSystemSettings(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,true);}
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);render();if(document!=null)SyncNotifications.changes(this,document);}
    final Runnable homeRefresh=new Runnable(){public void run(){if(isDestroyed())return;File f=new File(getFilesDir(),"schedule.json");if(f.lastModified()!=renderedFileTime){loadLocal();render();}handler.postDelayed(this,15000);}};
    @Override protected void onResume(){super.onResume();if(homeWeb!=null)homeWeb.onResume();loadLocal();AutoSync.ensure(this);render();handler.removeCallbacks(homeRefresh);handler.postDelayed(homeRefresh,15000);}
    @Override protected void onPause(){handler.removeCallbacks(homeRefresh);if(homeWeb!=null)homeWeb.onPause();super.onPause();}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);openChanges=intent.getBooleanExtra("openChanges",false);loadLocal();render();}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    void alert(String title,String message){if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了",null).show();}
    // 设备端诊断记录：WebView 只能在本机复现，失败时由用户回传这几十行日志。
    final java.util.ArrayDeque<String> trace=new java.util.ArrayDeque<>();
    // 0.2.6 起 worker 线程（Java 侧抓取）也会写 trace，需线程安全。
    synchronized void trace(String m){try{while(trace.size()>80)trace.removeFirst();trace.addLast(new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.US).format(new java.util.Date())+"  "+m);android.util.Log.i("HQUTT",m);}catch(Exception e){}}
    synchronized String traceText(){if(trace.isEmpty())return "（暂无记录）";StringBuilder b=new StringBuilder();for(String s:trace)b.append(s).append('\n');return b.toString();}
    void showTrace(){
        TextView t=new TextView(this);t.setText(traceText());t.setTextSize(10);t.setTextIsSelectable(true);t.setPadding(dp(12),dp(8),dp(12),dp(8));
        ScrollView s=new ScrollView(this);s.addView(t);
        if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("同步诊断（长按可复制）").setView(s).setPositiveButton("关闭",null).show();
    }
    void alertFailed(String title,String message){
        if(isFinishing()||isDestroyed())return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("知道了",null)
            .setNeutralButton("查看诊断",(d,w)->showTrace()).show();
    }
    void help(){alert("怎样使用", "1. 首次使用请点“账号设置”填写学号和密码（仅保存在本机）。\n2. 点“一键同步课表”：应用会自动完成统一认证登录（含滑块验证码识别）、进入教务并抓取当前学期完整课表，自动保存为 JSON。\n3. 同步结果按周显示，可左右切换周次；“导出 JSON”可备份。\n\n自动登录使用校方统一认证页面完成，凭据只保存在应用私有目录，不上传任何服务器。若滑块识别偶发失败，可在页面手动完成后点“继续同步”。\n\n课表数据来自教务系统“我的课表”接口，覆盖整学期（含周次、节次、教室）。个别调课、临时安排请以教务页面为准。");}
    void loadLocal(){File file=new File(getFilesDir(),"schedule.json");if(!file.exists())return;try(FileInputStream in=new FileInputStream(file)){document=new JSONObject(read(in));}catch(Exception e){alert("本地课表无法读取","请从备份重新导入，或重新同步。");}}
    void loadCreds(){File file=new File(getFilesDir(),"credentials.json");if(!file.exists())return;try(FileInputStream in=new FileInputStream(file)){creds=new JSONObject(read(in));}catch(Exception e){creds=null;}}
    boolean haveCreds(){return creds!=null&&creds.optString("username","").length()>0&&creds.optString("password","").length()>0;}
    void saveCreds(String user,String pass){
        if(user.isEmpty()||pass.isEmpty()){toast("学号和密码都要填写。");return;}
        try{creds=new JSONObject();creds.put("username",user);creds.put("password",pass);
            try(FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"credentials.json"))){out.write(creds.toString().getBytes(StandardCharsets.UTF_8));}
            toast("账号已保存（仅本机）。");
        }catch(Exception e){toast("保存失败，请重试。");}}
    void clearCreds(){creds=null;new File(getFilesDir(),"credentials.json").delete();toast("本机账号已清除。");}
    String read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>3*1024*1024)throw new IOException("Too large");out.write(buf,0,n);}return out.toString("UTF-8");}
    String readAsset(String name)throws IOException{try(InputStream in=getAssets().open(name)){return read(in);}}
    void render(){
        if(!homeReady||homeWeb==null)return;
        try{
            android.content.SharedPreferences pref=AutoSync.prefs(this);
            JSONObject state=new JSONObject();state.put("document",document==null?JSONObject.NULL:document);
            state.put("busy",busy);state.put("enabled",AutoSync.enabled(this));state.put("daytime",pref.getBoolean("daytime",true));
            state.put("notifications",SyncNotifications.allowed(this));state.put("status",pref.getString("status",""));
            state.put("lastSuccess",pref.getLong("lastSuccess",0)>0?AutoSync.stamp(pref.getLong("lastSuccess",0)):"");
            state.put("lastAttempt",pref.getLong("lastAttempt",0)>0?AutoSync.stamp(pref.getLong("lastAttempt",0)):"");
            state.put("nextCheck",AutoSync.stamp(pref.getLong("nextCheck",0)));state.put("scheduleFailed",pref.getBoolean("scheduleFailed",false));
            JSONArray history=document==null?null:document.optJSONArray("change_history");
            String latest=history!=null&&history.optJSONObject(0)!=null?history.optJSONObject(0).optString("id"):"";
            state.put("unread",!latest.isEmpty()&&!latest.equals(pref.getString("seen","")));state.put("openChanges",openChanges);openChanges=false;
            renderedFileTime=new File(getFilesDir(),"schedule.json").lastModified();
            String data=android.util.Base64.encodeToString(state.toString().getBytes(StandardCharsets.UTF_8),android.util.Base64.NO_WRAP);
            homeWeb.evaluateJavascript("window.renderModel(JSON.parse(new TextDecoder().decode(Uint8Array.from(atob('"+data+"'),c=>c.charCodeAt(0)))))",null);
        }catch(Exception e){trace("home render failed: "+e.getClass().getSimpleName());}
    }
    String join(JSONArray a,String fallback){if(a==null||a.length()==0)return fallback;StringBuilder s=new StringBuilder();for(int i=0;i<a.length();i++){if(i>0)s.append(",");s.append(a.optInt(i));}return s.toString();}
    boolean university(String url){return LoginNavigation.classify(url,false)==LoginNavigation.Action.ALLOW;}
    @SuppressWarnings("deprecation")
    void autoFlow(){
        if(busy){toast("正在处理，请稍候。");return;}
        if(!haveCreds()){showSettings(true);return;}
        wantSync=true; // Late successful navigation can still continue automatically.
        showBrowser();
        if(waitingSession){browserStatus.setText("正在等待教务建立登录会话…");return;}
        startAutoLogin();
    }
    void startAutoLogin(){
        long now=SystemClock.elapsedRealtime();
        if(pendingAutoLogin&&loginFlow.waiting(now)){
            browserStatus.setText("登录仍在处理中，等待教务跳转；请勿重复提交。");
            trace("reuse active login attempt #"+loginFlow.attempt);return;
        }
        loginFlow.begin(now);loginGen++;lastLoginTrace="";
        pendingAutoLogin=true;pendingSync=false;loginInjects=0;loginPolls=0;httpUpgradeCount=0;
        waitingSession=false;sessionWaitGen++;
        boolean wasLoggedOut=loggedOutFlag;loggedOutFlag=false;
        if(initialLoadPending){browserStatus.setText("正在打开统一身份认证…");return;}
        String cur=web.getUrl();
        if(!wasLoggedOut&&cur!=null&&cur.startsWith("https://jwapp.hqu.edu.cn/")&&
            LoginFlow.cookieState(CookieManager.getInstance().getCookie(COOKIE_URL),0)==LoginFlow.CookieState.READY){
            trace("jwapp session available -> java fetch");pendingAutoLogin=false;loginFlow.stop();javaFetch();return;
        }
        // A new explicit attempt needs a fresh CAS document and salt, never the old result.
        browserStatus.setText("正在重新打开统一身份认证…");
        trace("load LOGIN_URL (fresh attempt #"+loginFlow.attempt+")");web.loadUrl(LOGIN_URL);
    }
    @SuppressWarnings("deprecation")
    void showBrowser(){
        home.setVisibility(View.GONE);
        if(browserPanel!=null){browserPanel.setVisibility(View.VISIBLE);return;}
        browserPanel=column();browserPanel.setPadding(dp(8),dp(4),dp(8),0);root.addView(browserPanel,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout top=row();addButton(top,button("返回",false,()->{trace("btn 返回课表");hideBrowser();}));addButton(top,button("同步",false,()->{trace("btn 继续同步");continueSync();}));addButton(top,button("刷新",false,()->{trace("btn 刷新");web.reload();}));addButton(top,button("诊断",false,()->{trace("btn 诊断");showTrace();}));addButton(top,button("退出",false,()->{trace("btn 退出登录");logout();}));browserPanel.addView(top);
        browserStatus=text("正在准备登录…",12,MUTED);browserPanel.addView(browserStatus);
        web=new WebView(this);web.getSettings().setUserAgentString(DESKTOP_UA);web.setBackgroundColor(Color.WHITE);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);web.getSettings().setSupportZoom(true);web.getSettings().setBuiltInZoomControls(true);web.getSettings().setDisplayZoomControls(false);web.getSettings().setLoadWithOverviewMode(true);web.getSettings().setUseWideViewPort(true);web.getSettings().setSaveFormData(false);web.getSettings().setSavePassword(false);web.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap favicon){trace("start "+brief(url));}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){return req.isForMainFrame()?decideNav(req.getUrl().toString(),"POST".equalsIgnoreCase(req.getMethod())):!university(req.getUrl().toString());}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return decideNav(url,false);}
            @Override public void onPageFinished(WebView view,String url){handlePageFinished(url);}
            @Override public void onReceivedError(WebView view,WebResourceRequest req,WebResourceError err){if(req.isForMainFrame()){trace("net-error code="+err.getErrorCode()+" "+brief(req.getUrl().toString()));browserStatus.setText("页面连接失败，请检查网络或校园网；可点击刷新。");}}
            @Override public void onReceivedHttpError(WebView view,WebResourceRequest req,WebResourceResponse response){if(req.isForMainFrame()){trace("http-error "+response.getStatusCode()+" "+brief(req.getUrl().toString()));browserStatus.setText("服务器返回 "+response.getStatusCode()+"，暂时无法读取。");}}
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,android.net.http.SslError error){handler.cancel();browserStatus.setText("校方页面证书校验失败，已停止连接。");}
        });
        browserPanel.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        // 首次创建时立即走 CAS 入口：已登录 → 自动跳到 jwapp；
        // 未登录 → 显示登录页。后续调用（panel 已存在）不再 loadUrl，
        // 由 startAutoLogin 根据当前状态决定下一步。
        initialLoadPending=true;
        trace("load LOGIN_URL (initial)");
        web.loadUrl(LOGIN_URL);
    }
    // Log scheme/port/path, never queries, credentials or tickets.
    String brief(String url){return LoginNavigation.brief(url);}
    boolean decideNav(String url,boolean post){
        LoginNavigation.Action action=LoginNavigation.classify(url,post);
        if(action==LoginNavigation.Action.ALLOW)return false;
        if(action==LoginNavigation.Action.UPGRADE_CAS&&httpUpgradeCount++==0){
            trace("upgrade CAS entry to fixed HTTPS: "+brief(url));
            waitingSession=false;sessionWaitGen++;loginFlow.begin(SystemClock.elapsedRealtime());
            loginGen++;loginInjects=0;pendingAutoLogin=wantSync&&haveCreds();
            browserStatus.setText("认证入口返回了 HTTP，正在重新打开 HTTPS 登录入口…");
            web.loadUrl(LOGIN_URL);return true;
        }
        trace("block navigation: "+brief(url)+" post="+post);
        browserStatus.setText("已停止不受支持的跳转；请查看诊断中的协议和端口。");return true;
    }
    void handlePageFinished(String url){
        Uri u=Uri.parse(url);String host=u.getHost()==null?"":u.getHost();String path=u.getPath()==null?"":u.getPath();
        trace("page "+brief(url)+"  auto="+pendingAutoLogin+" sync="+pendingSync+" inj="+loginInjects);
        initialLoadPending=false; // 任何页面完成加载都清除"待初始加载"标志
        if(host.equals("jwapp.hqu.edu.cn")){
            if(pendingAutoLogin){pendingAutoLogin=false;loginGen++;trace("jwapp reached; checking session");}
            loginFlow.stop();
            if(wantSync&&!busy){trace("jwapp arrived -> check cookies");javaFetch();}
        }else if(pendingAutoLogin&&host.equals("id.hqu.edu.cn")&&path.contains("/authserver")){
            // 0.2.3 诊断：首个 onPageFinished 时 DOM 未就绪导致 no_form，同页第二个
            // onPageFinished 又被 3 秒去重拦住。脚本内部现在会等待表单就绪，JS 侧
            // __LOGIN_RUNNING 守卫防止同页双跑，故不再做时间去重，注入上限放宽到 3。
            if(loginInjects<3)injectLogin();
            else{pendingAutoLogin=false;loginFlow.stop();browserStatus.setText("自动登录未完成；可在此页手动登录后点“同步”。");}
        }else if(!busy){
            browserStatus.setText(host.isEmpty()?"页面加载中":("页面："+host+" · 可点“继续同步”刷新课表"));
        }
    }
    void injectLogin(){
        if(!haveCreds()){pendingAutoLogin=false;browserStatus.setText("请先在“账号设置”填写学号密码。");return;}
        loginInjects++;
        trace("inject auto_login.js #"+loginInjects);
        try{
            String script=readAsset("auto_login.js")
                .replace("__ATTEMPT__",String.valueOf(loginFlow.attempt))
                .replace("__USER__",JSONObject.quote(creds.optString("username")))
                .replace("__PASS__",JSONObject.quote(creds.optString("password")));
            browserStatus.setText("正在自动登录：识别滑块验证码…");
            web.evaluateJavascript(script,null);
            loginPolls=0;
            final int g=++loginGen;
            handler.postDelayed(()->pollLoginResult(g),1200);
        }catch(IOException e){pendingAutoLogin=false;failed("内置登录脚本读取失败。");}
    }
    void pollLoginResult(int g){
        if(g!=loginGen)return; // 已有更新的注入链，本链作废
        if(!pendingAutoLogin)return;
        if(!loginFlow.waiting(SystemClock.elapsedRealtime())){
            pendingAutoLogin=false;loginFlow.stop();
            web.evaluateJavascript("window.__LOGIN_ATTEMPT=-1;window.__LOGIN_RUNNING=false;",null);
            trace("login wait timed out; result unconfirmed");
            browserStatus.setText("等待登录跳转超时，尚未确认结果；可稍后点同步重新尝试，或手动登录。");return;
        }
        loginPolls++;
        if(loginPolls==20)browserStatus.setText("正在识别滑块验证码… 可能需要一分钟");
        web.evaluateJavascript("window.__LOGIN_RESULT",result->{
            if(g!=loginGen)return;
            if(result==null||"null".equals(result)){handler.postDelayed(()->pollLoginResult(g),1000);return;}
            try{
                JSONObject r=new JSONObject((String)new JSONTokener(result).nextValue());
                if(r.optInt("attempt",-1)!=loginFlow.attempt){handler.postDelayed(()->pollLoginResult(g),1000);return;}
                String rs=r.toString();
                if(!rs.equals(lastLoginTrace)){lastLoginTrace=rs;trace("login -> "+rs.substring(0,Math.min(200,rs.length())));}
                if(r.optBoolean("ok")){
                    loginFlow.submitted(SystemClock.elapsedRealtime());
                    browserStatus.setText("登录已提交，等待教务跳转…");
                    // 继续轮询以捕捉“提交后仍停留在登录页”的自检结果。
                    handler.postDelayed(()->pollLoginResult(g),1500);
                }
                else{
                    String stage=r.optString("stage"),detail=r.optString("detail","");
                    if("no_form".equals(stage)){
                        // 页面尚未就绪或结构变化（脚本已内部等待 10s）。不终止自动
                        // 流程：保持 pendingAutoLogin，等下一个页面事件重注入。
                        browserStatus.setText("登录页尚未就绪（"+detail+"）；可手动登录，成功后会自动继续同步。");
                        trace("login no_form (auto kept alive)");
                        if(loginInjects<3)handler.postDelayed(()->{if(g==loginGen&&pendingAutoLogin)injectLogin();},2000);
                        else{pendingAutoLogin=false;loginFlow.stop();}
                    }else{
                        pendingAutoLogin=false;loginFlow.stop();
                        String msg;
                        if("captcha".equals(stage))msg="滑块自动识别未成功（"+detail+"）；可手动完成登录后点“继续同步”。";
                        else if("server_rejected".equals(stage))msg="登录页面提示："+detail+"。可手动处理，或返回后重新同步。";
                        else if("invalid_action".equals(stage))msg="认证表单指向了不受支持的地址，已停止提交；请重新打开登录页。";
                        else if("error".equals(stage))msg="自动登录异常（"+detail+"）。";
                        else msg="自动登录未成功；可手动完成登录后点“继续同步”。";
                        browserStatus.setText(msg);
                        trace("login STOPPED: "+msg);
                        alertFailed("自动登录未完成",msg);
                    }
                }
            }catch(Exception e){handler.postDelayed(()->pollLoginResult(g),1000);}
        });
    }
    void continueSync(){
        if(busy){toast("正在处理，请稍候。");return;}
        wantSync=true;
        if(pendingAutoLogin&&loginFlow.waiting(SystemClock.elapsedRealtime())){
            browserStatus.setText("登录仍在处理中，成功后会自动同步。");trace("continue: waiting for active login");return;
        }
        String cookies=CookieManager.getInstance().getCookie(COOKIE_URL);
        if(LoginFlow.cookieState(cookies,0)==LoginFlow.CookieState.READY){trace("继续同步 -> java fetch");javaFetch();}
        else if(waitingSession){browserStatus.setText("正在等待教务建立会话…");}
        else{trace("continue: no session -> fresh login");startAutoLogin();}
    }
    void awaitSession(){
        if(waitingSession)return;
        waitingSession=true;sessionWaitStarted=SystemClock.elapsedRealtime();
        int generation=++sessionWaitGen;
        trace("session wait start (up to 30s)");pollSession(generation);
    }
    void pollSession(int generation){
        if(generation!=sessionWaitGen)return;
        if(!wantSync||isDestroyed()){waitingSession=false;return;}
        String cookies=CookieManager.getInstance().getCookie(COOKIE_URL);
        long elapsed=SystemClock.elapsedRealtime()-sessionWaitStarted;
        LoginFlow.CookieState state=LoginFlow.cookieState(cookies,elapsed);
        if(state==LoginFlow.CookieState.READY){
            waitingSession=false;trace("session ready cookies="+cookieNames(cookies));javaFetch();return;
        }
        if(state==LoginFlow.CookieState.TIMEOUT){
            waitingSession=false;loginFlow.stop();
            trace("session missing after wait; current="+brief(web==null?null:web.getUrl()));
            failed("认证跳转后尚未建立教务会话，请重新同步或完成页面中的登录。旧课表已保留。");return;
        }
        browserStatus.setText("已到达教务页面，正在等待登录会话建立…（"+(elapsed/1000)+"秒）");
        handler.postDelayed(()->pollSession(generation),1000);
    }
    // WebView has already opened the authenticated wdkb page. Query its APIs directly.
    // Each request reads URL-scoped cookies and completes server cookie updates before continuing.
    void javaFetch(){
        // Cookie path must match the data endpoint. The jwapp session is not visible at "/".
        final String cookies=CookieManager.getInstance().getCookie(COOKIE_URL);
        if(LoginFlow.cookieState(cookies,0)!=LoginFlow.CookieState.READY){awaitSession();return;}
        waitingSession=false;sessionWaitGen++;loginFlow.stop();
        trace("java fetch start  cookies="+cookieNames(cookies));
        busy=true;pendingSync=true;render();
        if(browserStatus!=null)browserStatus.setText("正在获取当前学期课表…");
        worker.execute(()->{
            try{
                String value=SyncEngine.sync(getApplicationContext(),this::trace).toString();
                runOnUiThread(()->{pendingSync=false;handleResult(value);});
            }catch(Exception e){
                String msg=e.getMessage();if(msg==null||msg.isEmpty())msg=e.getClass().getSimpleName();
                final String m=msg;
                trace("java fetch FAILED: "+m);
                runOnUiThread(()->{pendingSync=false;failed(m);});
            }
        });
    }
    String cookieNames(String cookies){
        if(cookies==null||cookies.trim().isEmpty())return "(none)";
        StringBuilder b=new StringBuilder();
        for(String p:cookies.split(";")){String n=p.trim();int eq=n.indexOf('=');if(b.length()>0)b.append(',');b.append(eq>0?n.substring(0,eq):n);}
        return b.toString();
    }
    void showSettings(boolean continueAfter){
        EditText user=new EditText(this),pass=new EditText(this);
        user.setHint("学号");user.setSingleLine(true);user.setTextSize(14);
        pass.setHint("统一认证密码");pass.setSingleLine(true);pass.setTextSize(14);
        pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if(creds!=null){user.setText(creds.optString("username",""));pass.setText(creds.optString("password",""));}
        LinearLayout box=column();box.setPadding(dp(10),dp(6),dp(10),dp(2));
        box.addView(user);box.addView(pass);
        box.addView(text("仅保存在本机应用私有目录，用于自动登录；“清除账号”可删除。",11,MUTED));
        new AlertDialog.Builder(this).setTitle("教务账号").setView(box)
            .setPositiveButton("保存",(d,w)->{saveCreds(user.getText().toString().trim(),pass.getText().toString());if(continueAfter&&haveCreds())autoFlow();})
            .setNeutralButton("清除账号",(d,w)->clearCreds())
            .setNegativeButton("取消",null).show();
    }
    void hideBrowser(){wantSync=false;if(browserPanel!=null)browserPanel.setVisibility(View.GONE);home.setVisibility(View.VISIBLE);render();}
    void logout(){if(busy){toast("请等待本次同步结束。");return;}new AlertDialog.Builder(this).setTitle("退出本机登录？").setMessage("清除本应用保存的网页登录状态；离线课表仍保留。").setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->{loggedOutFlag=true;wantSync=false;pendingSync=false;pendingAutoLogin=false;loginFlow.stop();waitingSession=false;sessionWaitGen++;loginGen++;trace("logout: clearing session");web.stopLoading();CookieManager.getInstance().removeAllCookies(done->{CookieManager.getInstance().flush();WebStorage.getInstance().deleteAllData();web.clearCache(true);web.clearHistory();web.loadUrl(LOGIN_URL);toast("本机登录状态已清除");});}).show();}
    void ensurePython(){if(!Python.isStarted())Python.start(new AndroidPlatform(getApplicationContext()));}
    void failed(String message){busy=false;wantSync=false;render();if(isDestroyed())return;trace("FAILED "+message);if(browserStatus!=null)browserStatus.setText("未同步成功，旧课表未改变。");alertFailed("未完成同步",message);}
    void handleResult(String value){busy=false;wantSync=false;if(isDestroyed())return;try{JSONObject result=new JSONObject(value);if(!result.optBoolean("ok")){failed(result.optString("error"));return;}document=result.getJSONObject("document");if(browserStatus!=null)browserStatus.setText("已保存 schedule.json。");hideBrowser();AutoSync.ensure(this);if(!AutoSync.prefs(this).getBoolean("permissionAsked",false))notificationPermission(false);toast("课表已更新，首页已按当前时间显示。");}catch(Exception e){failed("返回数据无法读取。");}}
    void export(){if(document==null){toast("请先同步或导入课表。");return;}Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,"hqu-schedule.json");startActivityForResult(i,10);}
    void importFile(){if(busy){toast("请等待同步结束。");return;}Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain"});startActivityForResult(i,11);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(request==10){if(document==null)return;final String exported=document.toString();worker.execute(()->{try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException();out.write(exported.getBytes(StandardCharsets.UTF_8));runOnUiThread(()->toast("JSON 已导出到所选位置。"));}catch(Exception e){runOnUiThread(()->alert("导出失败","请重试并选择一个可写入的位置。"));}});}else if(request==11){new AlertDialog.Builder(this).setTitle("导入课表？").setMessage("格式验证成功后将替换当前离线课表，建议先导出备份。").setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->{busy=true;worker.execute(()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();String content=read(in);String value=SyncEngine.importJson(getApplicationContext(),content);runOnUiThread(()->handleResult(value));}catch(Exception e){runOnUiThread(()->failed("文件读取失败，请检查文件类型及大小。"));}});}).show();}}
    @Override public void onBackPressed(){if(browserPanel!=null&&browserPanel.getVisibility()==View.VISIBLE){if(web.canGoBack())web.goBack();else hideBrowser();}else if(homeWeb!=null){homeWeb.evaluateJavascript("window.homeBack&&window.homeBack()",value->{if(!"true".equals(value))finish();});}else super.onBackPressed();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);if(web!=null){web.stopLoading();web.destroy();}if(homeWeb!=null){homeWeb.removeJavascriptInterface("App");homeWeb.destroy();}worker.shutdown();super.onDestroy();}
}
