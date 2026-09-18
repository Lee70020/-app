package cn.hqu.timetable.local;

/** Monotonic-clock policy, shared by UI handling and offline regressions. */
final class LoginFlow {
    int attempt;
    private boolean active, submitted;
    private long deadline;
    int begin(long now){attempt++;active=true;submitted=false;deadline=now+180000;return attempt;}
    void submitted(long now){if(active&&!submitted){submitted=true;deadline=now+120000;}}
    boolean waiting(long now){return active&&now<deadline;}
    void stop(){active=false;}
    enum CookieState { READY, WAIT, TIMEOUT }
    static CookieState cookieState(String cookies,long elapsed){
        if(cookies!=null)for(String part:cookies.split(";")){
            int split=part.indexOf('=');if(split<0)continue;
            String name=part.substring(0,split).trim(),value=part.substring(split+1).trim();
            if(!value.isEmpty()&&(name.equals("GS_SESSIONID")||name.equals("JSESSIONID")||name.equals("_WEU")))return CookieState.READY;
        }
        return elapsed>=30000?CookieState.TIMEOUT:CookieState.WAIT;
    }
}
