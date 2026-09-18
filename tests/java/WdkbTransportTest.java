package cn.hqu.timetable.local;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** Offline HTTP fixtures: no school requests and no real credentials. */
public final class WdkbTransportTest {
    static final String BASE="https://jwapp.hqu.edu.cn";
    static final String TERMS=BASE+"/jwapp/sys/wdkb/modules/xskcb/cxxljc.do";
    static final String KB=BASE+"/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do";
    static final ArrayDeque<Reply> replies=new ArrayDeque<>();
    static final List<String> opened=new ArrayList<>();
    static int passed,failed;
    interface Check { void run() throws Exception; }
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    static void test(String name,Check check){
        replies.clear();opened.clear();
        try{check.run();passed++;System.out.println("PASS "+name);}
        catch(Throwable e){failed++;System.out.println("FAIL "+name+": "+e);}
    }
    static final class Jar implements WdkbTransport.Cookies {
        final java.net.CookieManager jar=new java.net.CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER);
        public String get(String url)throws IOException{return String.join("; ",jar.get(URI.create(url),Collections.emptyMap()).getOrDefault("Cookie",Collections.emptyList()));}
        public void set(String url,String value)throws IOException{jar.put(URI.create(url),Collections.singletonMap("Set-Cookie",Collections.singletonList(value)));}
    }
    static final class Reply extends HttpURLConnection {
        final int status;final String body;final Map<String,List<String>> headers=new LinkedHashMap<>();
        final ByteArrayOutputStream sentBody=new ByteArrayOutputStream();
        Consumer<Reply> check=r->{};boolean disconnected;
        Reply(int code,String content)throws Exception{super(new URL(TERMS));status=code;body=content;}
        Reply header(String key,String value){headers.put(key,Collections.singletonList(value));return this;}
        Reply check(Consumer<Reply> value){check=value;return this;}
        public int getResponseCode(){check.accept(this);return status;}
        public Map<String,List<String>> getHeaderFields(){return headers;}
        public String getHeaderField(String key){return headers.getOrDefault(key,Collections.singletonList(null)).get(0);}
        public InputStream getInputStream(){return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));}
        public OutputStream getOutputStream(){return sentBody;}
        public void disconnect(){disconnected=true;}
        public boolean usingProxy(){return false;}
        public void connect(){}
    }
    static Reply reply(int code,String body)throws Exception{Reply r=new Reply(code,body);replies.add(r);return r;}
    static WdkbTransport client(Jar jar,List<String> log){return new WdkbTransport(jar,log::add);}
    static void rejects(WdkbTransport client,String url)throws Exception{
        try{client.exchange("POST",url,"x=1","test-UA","terms");throw new AssertionError("expected rejection");}
        catch(IOException expected){}
    }
    public static void main(String[] args)throws Exception{
        URL.setURLStreamHandlerFactory(protocol->"https".equals(protocol)?new URLStreamHandler(){
            protected URLConnection openConnection(URL url)throws IOException{
                opened.add(url.toString());Reply r=replies.poll();
                if(r==null)throw new AssertionError("unexpected request "+url.getPath());
                return r;
            }
        }:null);
        test("ordinary same-origin redirect reaches data",()->{
            reply(302,"").header("Location","/jwapp/data?ticket=SECRET_TICKET");
            reply(200,"data");
            String result=client(new Jar(),new ArrayList<>()).exchange("GET",TERMS,null,"test-UA","terms");
            require(result.equals("data")&&opened.size()==2,"redirect did not reach data");
        });
        test("select cookies per URL and accept redirect rotation",()->{
            Jar jar=new Jar();jar.set(TERMS,"SID=old; Path=/jwapp/sys/wdkb/modules; Secure; HttpOnly");
            reply(302,"").header("Location","/jwapp/registered").header("Set-Cookie","TOKEN=fresh; Path=/jwapp/registered; Secure; HttpOnly")
                .check(r->require(r.getRequestProperty("Cookie").contains("SID=old"),"scoped session omitted"));
            reply(200,"ok").check(r->{String c=r.getRequestProperty("Cookie");require(c.contains("TOKEN=fresh")&&!c.contains("SID=old"),"cookie path/rotation lost");});
            require(client(jar,new ArrayList<>()).exchange("GET",TERMS,null,"UA","terms").equals("ok"),"no data");
        });
        test("cookie update on 200 is used by next endpoint",()->{
            Jar jar=new Jar();jar.set(TERMS,"SID=old; Path=/jwapp; Secure; HttpOnly");
            reply(200,"terms").header("Set-Cookie","SID=new; Path=/jwapp; Secure; HttpOnly");
            reply(200,"rows").check(r->require(r.getRequestProperty("Cookie").contains("SID=new"),"stale snapshot sent"));
            WdkbTransport c=client(jar,new ArrayList<>());c.exchange("POST",TERMS,"","UA","terms");
            require(c.exchange("POST",KB,"XNXQDM=2026-2027-1","UA","kb").equals("rows"),"next request failed");
        });
        test("CAS redirect is reported without forwarding credentials",()->{
            reply(302,"").header("Location","https://id.hqu.edu.cn/authserver/login?ticket=SECRET_TICKET");
            try{client(new Jar(),new ArrayList<>()).exchange("GET",TERMS,null,"UA","terms");throw new AssertionError("expected auth error");}
            catch(IOException e){require(e.getMessage().contains("登录")&&e.getMessage().contains("terms")&&!e.getMessage().contains("SECRET_TICKET"),"bad auth diagnosis");}
            require(opened.size()==1,"sent request outside jwapp");
        });
        test("reject foreign host, cleartext and URL credentials before following",()->{
            for(String target:List.of("https://evil.example/test","http://jwapp.hqu.edu.cn/test","https://user@jwapp.hqu.edu.cn/test")){
                opened.clear();reply(302,"").header("Location",target);rejects(client(new Jar(),new ArrayList<>()),TERMS);
                require(opened.size()==1,"unsafe redirect followed");
            }
        });
        test("307 preserves POST body and browser API headers",()->{
            reply(307,"").header("Location","/jwapp/moved");
            reply(200,"rows").check(r->{require(r.getRequestMethod().equals("POST"),"lost POST");require(r.sentBody.toString(StandardCharsets.UTF_8).equals("x=1"),"lost body");require((BASE+"/jwapp/sys/wdkb/*default/index.do?EMAP_LANG=zh").equals(r.getRequestProperty("Referer")),"missing referer");require(BASE.equals(r.getRequestProperty("Origin")),"missing origin");});
            client(new Jar(),new ArrayList<>()).exchange("POST",TERMS,"x=1","UA","kb");
        });
        test("303 follows as GET without reposting data",()->{
            reply(303,"").header("Location","/jwapp/result");
            reply(200,"rows").check(r->require(r.getRequestMethod().equals("GET")&&r.sentBody.size()==0,"reposted after 303"));
            client(new Jar(),new ArrayList<>()).exchange("POST",TERMS,"x=1","UA","kb");
        });
        test("redirect loops are bounded",()->{
            for(int i=0;i<10;i++)reply(302,"").header("Location",TERMS);
            rejects(client(new Jar(),new ArrayList<>()),TERMS);require(opened.size()<=6,"redirect loop unbounded");
        });
        test("diagnostics include step and status without tokens",()->{
            Jar jar=new Jar();jar.set(TERMS,"SID=SECRET_SESSION; Path=/jwapp; Secure");List<String> log=new ArrayList<>();
            reply(302,"").header("Location","/jwapp/next?ticket=SECRET_TICKET");reply(200,"data");
            client(jar,log).exchange("GET",TERMS,null,"UA","terms");String text=String.join("\n",log);
            require(text.contains("terms")&&text.contains("302")&&text.contains("200"),"insufficient diagnosis");
            require(!text.contains("SECRET_SESSION")&&!text.contains("SECRET_TICKET"),"secret logged");
        });
        test("HTTP error names the failed step",()->{
            Reply r=reply(403,"private body");
            try{client(new Jar(),new ArrayList<>()).exchange("POST",TERMS,"","UA","terms");throw new AssertionError("expected 403");}
            catch(IOException e){require(e.getMessage().contains("403")&&e.getMessage().contains("terms")&&!e.getMessage().contains("private body"),"unhelpful error");}
            require(r.disconnected,"connection leaked");
        });
        System.out.println("RESULT "+passed+" passed, "+failed+" failed");if(failed>0)System.exit(1);
    }
}
