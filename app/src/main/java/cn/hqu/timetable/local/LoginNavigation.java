package cn.hqu.timetable.local;
import java.net.URI;
import java.util.Locale;

final class LoginNavigation {
    enum Action { ALLOW, UPGRADE_CAS, BLOCK }
    static Action classify(String url,boolean post){
        try{
            URI u=new URI(url);String host=u.getHost(),scheme=u.getScheme();
            if(host==null||scheme==null||u.getRawUserInfo()!=null)return Action.BLOCK;
            host=host.toLowerCase(Locale.ROOT);scheme=scheme.toLowerCase(Locale.ROOT);
            if(scheme.equals("https")&&(u.getPort()==-1||u.getPort()==443)&&(host.equals("hqu.edu.cn")||host.endsWith(".hqu.edu.cn")))return Action.ALLOW;
            // Never transmit a request over cleartext: only return to our fixed HTTPS CAS entry.
            if(!post&&scheme.equals("http")&&(u.getPort()==-1||u.getPort()==80)&&host.equals("id.hqu.edu.cn")&&"/authserver/login".equals(u.getPath()))return Action.UPGRADE_CAS;
        }catch(Exception ignored){}
        return Action.BLOCK;
    }
    static String brief(String url){
        try{URI u=new URI(url);return String.valueOf(u.getScheme())+"://"+String.valueOf(u.getHost())+
            (u.getPort()==-1?"":":"+u.getPort())+String.valueOf(u.getRawPath());}
        catch(Exception e){return "invalid-url";}
    }
}
