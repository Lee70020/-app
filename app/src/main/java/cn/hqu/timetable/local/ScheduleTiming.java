package cn.hqu.timetable.local;
import java.util.Calendar;
import java.util.TimeZone;
final class ScheduleTiming {
    static long next(long now,boolean daytime){
        Calendar day=Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
        day.setTimeInMillis(now);day.set(Calendar.SECOND,0);day.set(Calendar.MILLISECOND,0);
        for(int d=0;d<8;d++){
            int[] minutes=daytime?new int[]{15,480,840,1200}:new int[]{15};
            for(int minute:minutes){
                if(minute==15&&day.get(Calendar.DAY_OF_WEEK)!=Calendar.MONDAY)continue;
                day.set(Calendar.HOUR_OF_DAY,minute/60);day.set(Calendar.MINUTE,minute%60);
                if(day.getTimeInMillis()>now)return day.getTimeInMillis();
            }
            day.add(Calendar.DATE,1);
        }
        throw new IllegalStateException("No next slot");
    }
}
