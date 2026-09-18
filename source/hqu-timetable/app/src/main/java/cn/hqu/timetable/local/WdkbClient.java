package cn.hqu.timetable.local;
import android.os.*;
import android.webkit.CookieManager;
import org.json.*;
import java.io.*;
import java.net.URLEncoder;
import java.util.concurrent.*;
final class WdkbClient{
        private final java.util.function.Consumer<String> logger;
        private final Handler handler=new Handler(Looper.getMainLooper());
        WdkbClient(java.util.function.Consumer<String> logger){this.logger=logger;}
        void trace(String text){logger.accept(text);}
        final String BASE="https://jwapp.hqu.edu.cn";
        final WdkbTransport http=new WdkbTransport(new WdkbTransport.Cookies(){
            public String get(String url){return CookieManager.getInstance().getCookie(url);}
            public void set(String url,String value)throws IOException{
                CountDownLatch done=new CountDownLatch(1);
                boolean[] failed={false};
                boolean queued=handler.post(()->{
                    try{
                        CookieManager.getInstance().setCookie(url,value,ok->{
                            if(!Boolean.TRUE.equals(ok))trace("http cookie update rejected");
                            done.countDown();
                        });
                    }catch(Exception e){failed[0]=true;done.countDown();}
                });
                try{
                    if(!queued||!done.await(5,TimeUnit.SECONDS)||failed[0])
                        throw new IOException("Cookie update did not complete");
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Cookie update interrupted");}
            }
        },this::trace);
        JSONArray rowsOf(JSONObject resp,String key){try{return resp.getJSONObject("datas").getJSONObject(key).getJSONArray("rows");}catch(Exception e){return null;}}
        String httpCookie(){return CookieManager.getInstance().getCookie(BASE+"/jwapp/sys/wdkb/modules/xskcb/cxxljc.do");}
        String fetch(String ua)throws Exception{
            String cookies=httpCookie();
            if(cookies==null||cookies.trim().isEmpty())throw new WdkbTransport.AuthException("教务会话不存在，请点“一键同步课表”登录。");
            JSONObject sj;
            try{sj=new JSONObject(http.exchange("POST",BASE+"/jwapp/sys/wdkb/modules/xskcb/cxxljc.do","",ua,"terms"));}
            catch(JSONException e){throw new IOException("教务返回了非学期数据（会话可能已失效），请重新一键同步。");}
            JSONArray terms=rowsOf(sj,"cxxljc");
            if(terms==null||terms.length()==0)throw new IOException("学期列表为空");
            long now=System.currentTimeMillis();JSONObject best=null;long bestTs=Long.MIN_VALUE;
            java.text.SimpleDateFormat df=new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US);
            df.setTimeZone(java.util.TimeZone.getTimeZone("Asia/Shanghai"));df.setLenient(false);
            for(int i=0;i<terms.length();i++){
                JSONObject r=terms.optJSONObject(i);if(r==null)continue;
                String s=r.optString("XQKSRQ","");
                if(s.length()<10)continue;
                try{long ts=df.parse(s.substring(0,10)).getTime();if(ts<=now&&ts>bestTs){bestTs=ts;best=r;}}catch(Exception ignore){}
            }
            if(best==null)best=terms.optJSONObject(0);
            if(best==null)throw new IOException("学期信息无法识别");
            String term=best.optString("XN")+"-"+best.optString("XQ");
            String display=best.optString("XN")+"学年第"+best.optString("XQ")+"学期";
            trace("java fetch: term="+term+"  candidates="+terms.length());
            String enc=URLEncoder.encode(term,"UTF-8");
            JSONObject kb;
            try{kb=new JSONObject(http.exchange("POST",BASE+"/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do","XNXQDM="+enc+"&XNXQDM2="+enc+"&XNXQDM3="+enc,ua,"kb"));}
            catch(JSONException e){throw new IOException("教务返回了非课表数据（会话可能已失效），请重新一键同步。");}
            JSONArray rows=rowsOf(kb,"cxxszhxqkb");
            if(rows==null)throw new IOException("课表查询失败（教务未返回数据）");
            trace("java fetch: kb rows="+rows.length());
            JSONObject out=new JSONObject();
            out.put("ok",true);out.put("term",term);out.put("termDisplay",display);
            out.put("termStart",best.optString("XQKSRQ", ""));
            out.put("rowCount",rows.length());out.put("rows",rows);
            return out.toString();
        }
    }
