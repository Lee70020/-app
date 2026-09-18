package cn.hqu.timetable.local;
public class LoginFlowTest {
    static int count;
    static void check(boolean ok){count++;if(!ok)throw new AssertionError("case "+count);}
    public static void main(String[] args){
        LoginFlow f=new LoginFlow();check(f.begin(0)==1);check(f.waiting(11000));check(f.waiting(179999));check(!f.waiting(180000));
        f.begin(200000);f.submitted(201000);check(f.waiting(270000));f.submitted(300000);check(!f.waiting(321000));
        f.stop();check(!f.waiting(200001));check(f.begin(400000)==3);
        check(LoginFlow.cookieState(null,0)==LoginFlow.CookieState.WAIT);
        check(LoginFlow.cookieState(null,29999)==LoginFlow.CookieState.WAIT);
        check(LoginFlow.cookieState(null,30000)==LoginFlow.CookieState.TIMEOUT);
        check(LoginFlow.cookieState("EMAP_LANG=zh",0)==LoginFlow.CookieState.WAIT);
        check(LoginFlow.cookieState("JSESSIONID=",0)==LoginFlow.CookieState.WAIT);
        check(LoginFlow.cookieState("EMAP_LANG=zh; JSESSIONID=fixture",25000)==LoginFlow.CookieState.READY);
        check(LoginNavigation.classify("https://id.hqu.edu.cn/authserver/login?service=fixture",false)==LoginNavigation.Action.ALLOW);
        check(LoginNavigation.classify("http://id.hqu.edu.cn/authserver/login?service=fixture",false)==LoginNavigation.Action.UPGRADE_CAS);
        check(LoginNavigation.classify("http://id.hqu.edu.cn:80/authserver/login",false)==LoginNavigation.Action.UPGRADE_CAS);
        check(LoginNavigation.classify("http://id.hqu.edu.cn/authserver/login",true)==LoginNavigation.Action.BLOCK);
        for(String url:new String[]{"https://id.hqu.edu.cn:444/authserver/login","http://evil.test/authserver/login","https://hqu.edu.cn.evil.test/","https://user@id.hqu.edu.cn/","intent://id.hqu.edu.cn/","http://jwapp.hqu.edu.cn/","http://id.hqu.edu.cn/other"})check(LoginNavigation.classify(url,false)==LoginNavigation.Action.BLOCK);
        String brief=LoginNavigation.brief("http://id.hqu.edu.cn:80/authserver/login?service=SECRET&ticket=PRIVATE");
        check(brief.startsWith("http://"));check(brief.contains(":80/"));check(!brief.contains("SECRET")&&!brief.contains("PRIVATE"));
        System.out.println("Login flow and navigation: "+count+" passed");
    }
}
