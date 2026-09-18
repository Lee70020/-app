package cn.hqu.timetable.local;

import android.app.job.*;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScheduleJobService extends JobService {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Run current;
    static class Run {final AtomicBoolean stopped=new AtomicBoolean();Future<?> future;}
    @Override public boolean onStartJob(JobParameters params){
        if(!AutoSync.enabled(this))return false;
        Run run=new Run();current=run;
        run.future=worker.submit(()->{
            boolean retry=false;
            try{SyncEngine.sync(getApplicationContext(),text->{
                android.util.Log.i("HQUTT",text);
                AutoSync.prefs(this).edit().putString("lastStep",text).apply();
            });}
            catch(WdkbTransport.AuthException e){/* Wait for user login; keep normal schedule. */}
            catch(Exception e){retry=true;}
            final boolean retryLater=retry;
            new Handler(Looper.getMainLooper()).post(()->{
                if(run.stopped.get())return;
                if(current==run)current=null;
                jobFinished(params,retryLater&&AutoSync.enabled(this));
                if(!retryLater)AutoSync.scheduleNext(this);
            });
        });
        return true;
    }
    @Override public boolean onStopJob(JobParameters params){
        if(current!=null){current.stopped.set(true);if(current.future!=null)current.future.cancel(true);current=null;}
        return AutoSync.enabled(this);
    }
    @Override public void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
