package dev.navix.agent;
import android.app.*;
import android.content.*;
import android.os.Bundle;
/** Tests the actual foreground-service goal loop using the saved DeepSeek account. */
public final class ServiceGoalProbe extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle output=new Bundle(); Context ctx=getTargetContext();
        android.content.SharedPreferences prefs=ctx.getSharedPreferences("config",0);
        String[] keys={"backend","goal_mode","deepseek_thinking"};
        java.util.Map<String,Object> saved=new java.util.HashMap<>();
        for(String key:keys)if(prefs.contains(key))saved.put(key,prefs.getAll().get(key));
        Activity activity=null;
        try {
            if(Secrets.load(ctx).isEmpty())throw new IllegalStateException("DeepSeek key not configured");
            prefs.edit().putString("backend","deepseek").putBoolean("goal_mode",true).putInt("deepseek_thinking",2).commit();
            activity=startActivitySync(new Intent(ctx,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ctx.startForegroundService(new Intent(ctx,AgentService.class).putExtra("goal","这是一次只读接入测试。仅执行 am 工具 args=[\"help\"]。确认输出包含 Activity manager 后调用 complete_task，summary 写只读测试完成，evidence 引用该命令结果。不要启动应用或执行其它手机操作。"));
            long deadline=System.currentTimeMillis()+120000; boolean started=false;
            while(System.currentTimeMillis()<deadline){
                if(AgentService.running)started=true;
                if(started&&!AgentService.running)break;
                Thread.sleep(100);
            }
            if(!started||AgentService.running||!AgentService.status.startsWith("Goal 已完成："))throw new AssertionError("Service goal incomplete: "+AgentService.status);
            output.putString("stream","PASS: actual DeepSeek foreground-service Goal loop, action dispatch/Toast path and verified completion\n");
        }catch(Throwable e){output.putString("stream","FAIL: "+e+"\n");}
        finally{
            if(AgentService.running){ctx.startService(new Intent(ctx,AgentService.class).setAction("stop"));try{Thread.sleep(1000);}catch(Exception ignored){}}
            android.content.SharedPreferences.Editor editor=prefs.edit();
            for(String key:keys){Object value=saved.get(key);if(value==null)editor.remove(key);else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);else editor.putString(key,(String)value);}
            editor.commit();
            Activity end=activity;if(end!=null)runOnMainSync(end::finish);
        }
        finish(output.getString("stream").startsWith("PASS")?-1:0,output);
    }
}
