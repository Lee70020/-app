package cn.hqu.timetable.local;

import android.app.job.*;
import android.content.*;
import android.os.PersistableBundle;
import java.util.*;

final class AutoSync {
    static final int JOB=3001;
    static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("auto_sync",Context.MODE_PRIVATE);}
    static boolean enabled(Context c){return prefs(c).getBoolean("enabled",true);}
    static void ensure(Context c){
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(!enabled(c)){scheduler.cancel(JOB);return;}
        if(scheduler.getPendingJob(JOB)==null)scheduleNext(c);
    }
    static void scheduleNext(Context c){
        if(!enabled(c))return;
        long now=System.currentTimeMillis();
        long next=ScheduleTiming.next(now,prefs(c).getBoolean("daytime",true));
        PersistableBundle extras=new PersistableBundle();extras.putLong("scheduledAt",next);
        JobInfo job=new JobInfo.Builder(JOB,new ComponentName(c,ScheduleJobService.class))
            .setMinimumLatency(Math.max(1000,next-now)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).setBackoffCriteria(30*60*1000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setExtras(extras).build();
        int result=c.getSystemService(JobScheduler.class).schedule(job);
        prefs(c).edit().putLong("nextCheck",next).putBoolean("scheduleFailed",result!=JobScheduler.RESULT_SUCCESS).apply();
    }
    static void configure(Context c,boolean enabled,boolean daytime){
        prefs(c).edit().putBoolean("enabled",enabled).putBoolean("daytime",daytime).apply();
        c.getSystemService(JobScheduler.class).cancel(JOB);
        if(enabled)scheduleNext(c);
    }
    static String stamp(long at){
        if(at<=0)return "尚未检查";
        java.text.SimpleDateFormat f=new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.CHINA);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));return f.format(new Date(at));
    }
}
