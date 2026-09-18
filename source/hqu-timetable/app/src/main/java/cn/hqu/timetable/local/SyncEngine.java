package cn.hqu.timetable.local;

import android.content.Context;
import android.webkit.CookieManager;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import org.json.*;
import java.io.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Shared front/background transaction. Network, comparison and atomic write are serialized. */
final class SyncEngine {
    private static final ReentrantLock LOCK=new ReentrantLock();
    private static void python(Context c){if(!Python.isStarted())Python.start(new AndroidPlatform(c.getApplicationContext()));}
    static JSONObject sync(Context c,Consumer<String> trace)throws Exception {
        LOCK.lockInterruptibly();
        try{
            AutoSync.prefs(c).edit().putLong("lastAttempt",System.currentTimeMillis()).apply();
            String payload=new WdkbClient(trace).fetch(MainActivity.DESKTOP_UA);
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            CookieManager.getInstance().flush();
            python(c);
            JSONObject result=new JSONObject(Python.getInstance().getModule("schedule")
                .callAttr("sync_wdkb",payload,c.getFilesDir().getAbsolutePath()).toString());
            if(!result.optBoolean("ok"))throw new IOException(result.optString("error","课表数据读取失败"));
            AutoSync.prefs(c).edit().putLong("lastSuccess",System.currentTimeMillis()).putString("status","ok")
                .putBoolean("authNotified",false).apply();
            try{SyncNotifications.cancelLogin(c);SyncNotifications.changes(c,result.getJSONObject("document"));}
            catch(Exception e){trace.accept("notification delivery unavailable; change history saved");}
            return result;
        }catch(WdkbTransport.AuthException e){
            AutoSync.prefs(c).edit().putString("status","login").apply();
            if(AutoSync.enabled(c))try{SyncNotifications.login(c);}catch(Exception ignored){}
            throw e;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw e;}
        catch(Exception e){AutoSync.prefs(c).edit().putString("status","error").apply();throw e;}
        finally{LOCK.unlock();}
    }
    static String importJson(Context c,String text)throws Exception {
        LOCK.lockInterruptibly();
        try{python(c);return Python.getInstance().getModule("schedule").callAttr("import_json",text,c.getFilesDir().getAbsolutePath()).toString();}
        finally{LOCK.unlock();}
    }
}
