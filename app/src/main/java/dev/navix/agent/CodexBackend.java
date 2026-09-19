package dev.navix.agent;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import org.json.*;

/** Credentials and model transport stay in the Termux user process. */
final class CodexBackend {
    interface ToolHandler { JSONObject execute(JSONObject call) throws Exception; }
    interface StatusHandler { void update(String text); }
    interface Guard { void check() throws Exception; }
    private volatile LocalSocket active;
    private volatile boolean cancelled, paused, bridgeReady;
    private final Object writes=new Object();
    void setPaused(boolean value) {
        paused=value;
        new Thread(() -> {
            synchronized(writes) {
                LocalSocket socket=active;
                if(socket!=null&&bridgeReady)try {
                    Wire.write(socket.getOutputStream(),new JSONObject().put("event","pause").put("paused",paused));
                } catch(Exception ignored) { }
            }
        },"navi-codex-pause").start();
    }
    void cancel() { cancelled=true; LocalSocket socket=active; if(socket!=null)try{socket.close();}catch(Exception ignored){} }
    private LocalSocket connect(Context context) throws Exception {
        if(cancelled)throw new InterruptedException();
        LocalSocket socket=new LocalSocket(); active=socket;
        try {
            socket.connect(new LocalSocketAddress(Wire.SOCKET)); socket.setSoTimeout(20000);
            if(socket.getPeerCredentials().getUid()!=0)throw new SecurityException("Root relay required");
            if(cancelled)throw new InterruptedException();
            return socket;
        }catch(Exception e){socket.close();throw e;}
    }
    String checkConnection(Context context) throws Exception {
        try(LocalSocket socket=connect(context)) {
            Wire.write(socket.getOutputStream(),new JSONObject().put("op","codex_bridge").put("request",new JSONObject().put("op","check")));
            while(true) {
                JSONObject event=Wire.read(socket.getInputStream());
                if(event.optString("event").equals("error"))throw new IllegalStateException(event.optString("message"));
                if(event.optString("event").equals("done")) return event.optBoolean("loggedIn") ? "Codex 已连接，登录有效" : "Codex 已连接；请在 Termux 执行 codex login";
            }
        } finally {bridgeReady=false; active=null;}
    }
    String run(Context context,String goal,String model,ToolHandler tools,StatusHandler status,Guard guard) throws Exception {
        return run(context,goal,model,"auto",false,tools,status,guard);
    }
    String run(Context context,String goal,String model,String thinking,boolean goalMode,ToolHandler tools,StatusHandler status,Guard guard) throws Exception {
        try(LocalSocket socket=connect(context)) {
            synchronized(writes) {
            Wire.write(socket.getOutputStream(),new JSONObject().put("op","codex_bridge").put("request",new JSONObject().put("paused",paused).put("op","start").put("goal",goal).put("model",model)
                .put("thinking",thinking).put("goalMode",goalMode).put("instructions",TaskPolicy.instructions(goalMode,context.getSharedPreferences("config",0).getBoolean("background_mode",false))).put("tools",TaskPolicy.tools(goalMode))));
            bridgeReady=true;
            }
            // Drain heartbeats even when the execution worker is paused.
            java.util.concurrent.BlockingQueue<JSONObject> events=new java.util.concurrent.LinkedBlockingQueue<>(64);
            Thread reader=new Thread(() -> {
                try {
                    while(!Thread.currentThread().isInterrupted()) {
                        JSONObject event=Wire.read(socket.getInputStream());
                        if(!"tick".equals(event.optString("event")))events.put(event);
                    }
                } catch(Exception e) {
                    if(!Thread.currentThread().isInterrupted())try {
                        events.put(new JSONObject().put("event","error").put("message","Codex 连接中断："+e.getClass().getSimpleName()));
                    } catch(Exception ignored) { }
                }
            },"navi-codex-events");
            reader.setDaemon(true); reader.start();
            int steps=0, failures=0;
            try { while(true) {
                guard.check(); if(cancelled)throw new InterruptedException();
                JSONObject event=events.poll(250,java.util.concurrent.TimeUnit.MILLISECONDS);
                if(event==null)continue;
                guard.check();
                switch(event.getString("event")) {
                    case "tick": break;
                    case "status": status.update(event.optString("text")); break;
                    case "error": throw new IllegalStateException(event.optString("message"));
                    case "done": return event.optString("text","Codex 任务结束");
                    case "tool": {
                        if(++steps>25&&!goalMode)throw new IllegalStateException("已达到 25 步限制");
                        status.update("Codex 第 "+steps+" 步："+event.getString("name"));
                        JSONObject call=new JSONObject().put("function",new JSONObject().put("name",event.getString("name"))
                            .put("arguments",event.getJSONObject("arguments").toString()));
                        JSONObject result=tools.execute(call); guard.check();
                        synchronized(writes) { Wire.write(socket.getOutputStream(),new JSONObject().put("event","result").put("id",event.get("id")).put("result",result)); }
                        failures=result.optBoolean("ok")?0:failures+1;
                        if(failures>=3) { if(!goalMode)throw new IllegalStateException("连续三次操作失败，已停止"); status.update("Goal 将重新观察并调整操作"); Thread.sleep(3000); }
                        break;
                    }
                    default: throw new IllegalStateException("Unknown Codex bridge event");
                }
            } } finally { reader.interrupt(); }
        } finally {bridgeReady=false; active=null;}
    }
}
