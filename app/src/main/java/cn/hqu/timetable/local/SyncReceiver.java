package cn.hqu.timetable.local;
import android.content.*;
public class SyncReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent i){
        if(Intent.ACTION_TIME_CHANGED.equals(i.getAction())){
            AutoSync.configure(c,AutoSync.enabled(c),AutoSync.prefs(c).getBoolean("daytime",true));
        }else AutoSync.ensure(c);
    }
}
