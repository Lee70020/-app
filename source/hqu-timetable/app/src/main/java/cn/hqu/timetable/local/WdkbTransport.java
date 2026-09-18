package cn.hqu.timetable.local;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

/** HTTP requests share WebView cookies, selected for each destination URL. */
final class WdkbTransport {
    interface Cookies {
        String get(String url) throws IOException;
        // Must complete the update before returning, including expiry/deletion.
        void set(String url,String value) throws IOException;
    }
    private static final String BASE="https://jwapp.hqu.edu.cn";
    private static final String REFERER=BASE+"/jwapp/sys/wdkb/*default/index.do?EMAP_LANG=zh";
    private final Cookies cookies;
    private final Consumer<String> trace;
    WdkbTransport(Cookies cookies,Consumer<String> trace){this.cookies=cookies;this.trace=trace;}
    private static class Failure extends IOException {Failure(String message){super(message);}}
    static final class AuthException extends Failure {AuthException(String message){super(message);}}
    private static boolean allowed(URL url){
        return "https".equalsIgnoreCase(url.getProtocol())&&"jwapp.hqu.edu.cn".equalsIgnoreCase(url.getHost())
            &&(url.getPort()==-1||url.getPort()==443)&&url.getUserInfo()==null;
    }
    private static String address(URL url){return url.getHost()+url.getPath();}
    private static String names(String value){
        if(value==null||value.trim().isEmpty())return "(none)";
        StringBuilder names=new StringBuilder();
        for(String part:value.split(";")){int i=part.indexOf('=');if(i>0){if(names.length()>0)names.append(',');names.append(part.substring(0,i).trim());}}
        return names.toString();
    }
    String exchange(String method,String url,String body,String ua,String step)throws IOException{
        try{
            URL current=new URL(url);
            for(int redirects=0;redirects<=5;redirects++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Request interrupted");
                if(!allowed(current))throw new Failure("已停止非教务 HTTPS 请求（"+step+"）");
                HttpURLConnection connection=(HttpURLConnection)current.openConnection();
                try{
                    connection.setConnectTimeout(15000);connection.setReadTimeout(25000);
                    connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
                    connection.setRequestMethod(method);
                    connection.setRequestProperty("User-Agent",ua);
                    connection.setRequestProperty("Referer",REFERER);
                    connection.setRequestProperty("Accept","application/json, text/javascript, */*; q=0.01");
                    String header=cookies.get(current.toString());
                    if(header!=null&&!header.trim().isEmpty())connection.setRequestProperty("Cookie",header);
                    trace.accept("http "+step+" "+method+" "+address(current)+" cookies="+names(header));
                    if(body!=null){
                        byte[] data=body.getBytes(StandardCharsets.UTF_8);
                        connection.setDoOutput(true);connection.setFixedLengthStreamingMode(data.length);
                        connection.setRequestProperty("Origin",BASE);
                        connection.setRequestProperty("X-Requested-With","XMLHttpRequest");
                        connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
                        try(OutputStream out=connection.getOutputStream()){out.write(data);}
                    }
                    int code=connection.getResponseCode();
                    if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Request interrupted");
                    trace.accept("http "+step+" status="+code);
                    int updates=0;
                    for(Map.Entry<String,List<String>> entry:connection.getHeaderFields().entrySet()){
                        if(entry.getKey()!=null&&entry.getKey().equalsIgnoreCase("Set-Cookie")&&entry.getValue()!=null){
                            for(String value:entry.getValue())if(value!=null){cookies.set(current.toString(),value);updates++;}
                        }
                    }
                    if(updates>0)trace.accept("http "+step+" cookie updates="+updates);
                    if(code==200){
                        try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                            byte[] buffer=new byte[8192];int n;
                            while((n=in.read(buffer))!=-1){if(out.size()+n>4*1024*1024)throw new Failure("响应数据过大（"+step+"）");out.write(buffer,0,n);}
                            return out.toString("UTF-8");
                        }
                    }
                    if(code==301||code==302||code==303||code==307||code==308){
                        String location=connection.getHeaderField("Location");
                        if(location==null||location.trim().isEmpty())throw new Failure("重定向缺少目标（"+step+"，HTTP "+code+"）");
                        URL next=new URL(current,location);
                        trace.accept("http "+step+" redirect="+address(next));
                        if(!allowed(next)){
                            if("https".equalsIgnoreCase(next.getProtocol())&&"id.hqu.edu.cn".equalsIgnoreCase(next.getHost()))
                                throw new AuthException("教务请求返回统一登录页（"+step+"，HTTP "+code+"），请重新登录后同步。");
                            throw new Failure("已停止非教务 HTTPS 重定向（"+step+"，HTTP "+code+"）");
                        }
                        if(code==303||((code==301||code==302)&&"POST".equals(method))){method="GET";body=null;}
                        current=next;
                        continue;
                    }
                    throw new Failure("教务系统返回 HTTP "+code+"（"+step+"）");
                }finally{connection.disconnect();}
            }
            throw new Failure("教务重定向次数过多（"+step+"）");
        }catch(Failure e){throw e;}
        catch(IOException e){throw new Failure("教务网络请求或会话更新失败（"+step+"）");}
    }
}
