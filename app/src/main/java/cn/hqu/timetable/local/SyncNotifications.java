package cn.hqu.timetable.local;
import android.app.*;
import android.content.*;
import android.os.Build;
import org.json.*;

final class SyncNotifications {
    private static final String CHANNEL="schedule_updates";
    static void channel(Context c){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel channel=new NotificationChannel(CHANNEL,"课表调整与同步",NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("课表发生变化或教务登录过期时提醒");
            c.getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
    static boolean allowed(Context c){
        if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return false;
        if(!c.getSystemService(NotificationManager.class).areNotificationsEnabled())return false;
        if(Build.VERSION.SDK_INT>=26){NotificationChannel ch=c.getSystemService(NotificationManager.class).getNotificationChannel(CHANNEL);if(ch!=null&&ch.getImportance()==NotificationManager.IMPORTANCE_NONE)return false;}
        return true;
    }
    static boolean send(Context c,int id,String title,String text,boolean changes){
        channel(c);if(!allowed(c))return false;
        Intent i=new Intent(c,MainActivity.class).putExtra("openChanges",changes).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending=PendingIntent.getActivity(c,id,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,CHANNEL):new Notification.Builder(c);
        b.setSmallIcon(android.R.drawable.ic_menu_my_calendar).setContentTitle(title).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(pending).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_EVENT);
        try{c.getSystemService(NotificationManager.class).notify(id,b.build());return true;}
        catch(SecurityException e){return false;}
    }
    static void changes(Context c,JSONObject document){
        JSONArray history=document.optJSONArray("change_history");if(history==null||history.length()==0)return;
        JSONObject latest=history.optJSONObject(0);if(latest==null)return;
        String id=latest.optString("id");if(id.isEmpty()||id.equals(AutoSync.prefs(c).getString("notified","")))return;
        JSONArray lines=latest.optJSONArray("lines");if(lines==null)return;
        StringBuilder text=new StringBuilder();for(int n=0;n<Math.min(6,lines.length());n++)text.append(lines.optString(n)).append('\n');
        text.append("点击查看调整记录，并以教务最新安排为准。");
        if(send(c,3101,"课表有变化 · "+latest.optInt("count")+" 条安排",text.toString(),true))
            AutoSync.prefs(c).edit().putString("notified",id).apply();
    }
    static void login(Context c){
        if(AutoSync.prefs(c).getBoolean("authNotified",false))return;
        if(send(c,3102,"需要重新登录教务","自动更新暂时无法读取课表，点击打开应用同步。旧课表已保留。",false))
            AutoSync.prefs(c).edit().putBoolean("authNotified",true).apply();
    }
    static void cancelLogin(Context c){c.getSystemService(NotificationManager.class).cancel(3102);}
}
