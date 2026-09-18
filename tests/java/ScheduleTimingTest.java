package cn.hqu.timetable.local;
import java.text.SimpleDateFormat;
import java.util.*;
public class ScheduleTimingTest {
    static SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US);
    static int count;
    static void test(String now,boolean daytime,String want)throws Exception {
        String actual=f.format(new Date(ScheduleTiming.next(f.parse(now).getTime(),daytime)));
        if(!actual.equals(want))throw new AssertionError(now+" -> "+actual+" expected "+want);
        count++;
    }
    public static void main(String[] args)throws Exception {
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        test("2026-09-20 23:59:59",true,"2026-09-21 00:15:00");
        test("2026-09-21 00:15:00",true,"2026-09-21 08:00:00");
        test("2026-09-21 08:00:00",true,"2026-09-21 14:00:00");
        test("2026-09-21 14:00:01",true,"2026-09-21 20:00:00");
        test("2026-09-21 20:00:00",true,"2026-09-22 08:00:00");
        test("2026-09-21 00:15:00",false,"2026-09-28 00:15:00");
        test("2026-12-31 23:59:00",false,"2027-01-04 00:15:00");
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        test("2026-09-20 23:59:59",true,"2026-09-21 00:15:00");
        System.out.println("ScheduleTiming: "+count+" passed");
    }
}
