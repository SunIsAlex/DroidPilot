package dev.navix.agent;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.widget.Toast;
import org.json.*;
import java.util.*;

/** Primary-display return cards. Removing a card never removes the actual app task. */
final class BackgroundTasks {
    private static java.lang.ref.WeakReference<Activity> host=new java.lang.ref.WeakReference<>(null);
    private static final Handler main=new Handler(Looper.getMainLooper());
    private static boolean fetching;
    private static String cardError="";
    static void attach(Activity activity) { host=new java.lang.ref.WeakReference<>(activity); refresh(); }
    static void refresh() { main.post(() -> {
        Activity activity=host.get();
        if(fetching||activity==null||activity.isFinishing()||activity.isDestroyed())return;
        fetching=true;
        new Thread(() -> {
            try {
                JSONObject status=Wire.call(new JSONObject().put("op","background_status"));
                if(!status.optBoolean("ok"))return;
                main.post(() -> sync(activity,status.optJSONArray("tasks")));
            } catch(Exception ignored) { /* Keep existing cards when the daemon is unavailable. */ }
            finally { main.post(() -> fetching=false); }
        },"background-cards").start();
    }); }
    static String label(Context context,JSONObject task) {
        String pkg=task.optString("package");
        try { return context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(pkg,0)).toString(); }
        catch(Exception ignored) { return pkg.isEmpty()?task.optString("component"):pkg; }
    }
    private static String key(JSONObject task) { return task.optInt("task_id")+":"+task.optString("component"); }
    private static void sync(Activity activity,JSONArray tasks) {
        if(tasks==null||activity.isFinishing()||activity.isDestroyed())return;
        try {
            ActivityManager manager=activity.getSystemService(ActivityManager.class);
            Set<String> wanted=new HashSet<>(),existing=new HashSet<>();
            for(int i=0;i<tasks.length();i++)wanted.add(key(tasks.getJSONObject(i)));
            for(ActivityManager.AppTask card:manager.getAppTasks()) {
                Intent intent=card.getTaskInfo().baseIntent;
                if(intent.getComponent()==null||!intent.getComponent().getClassName().equals(BackgroundTaskActivity.class.getName()))continue;
                String identity=intent.getIntExtra("task_id",-1)+":"+intent.getStringExtra("component");
                if(!wanted.contains(identity)||!existing.add(identity))card.finishAndRemoveTask();
            }
            cardError="";
            for(int i=0;i<tasks.length();i++) {
                JSONObject task=tasks.getJSONObject(i); if(existing.contains(key(task)))continue;
                Intent intent=new Intent(activity,BackgroundTaskActivity.class)
                    .setData(android.net.Uri.parse("droidpilot://background/"+task.getInt("task_id")))
                    .putExtra("task_id",task.getInt("task_id")).putExtra("component",task.getString("component"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT|Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS);
                android.util.Size size=manager.getAppTaskThumbnailSize();
                Bitmap thumbnail=Bitmap.createBitmap(size.getWidth(),size.getHeight(),Bitmap.Config.ARGB_8888);
                thumbnail.eraseColor(Color.rgb(34,72,119));
                try {
                    int result=manager.addAppTask(activity,intent,new ActivityManager.TaskDescription(label(activity,task)+" · DroidPilot 后台",(Bitmap)null,Color.rgb(34,72,119)),thumbnail);
                    if(result<0)cardError="系统拒绝添加最近任务入口，可使用此列表返回应用。";
                } finally { thumbnail.recycle(); }
            }
        } catch(Exception error) { cardError="最近任务入口不可用，可使用此列表返回应用。"; }
    }
    static void show(Activity activity) {
        new Thread(() -> {
            try {
                JSONObject status=Wire.call(new JSONObject().put("op","background_status"));
                if(!status.optBoolean("ok"))throw new IllegalStateException(status.optString("error"));
                JSONArray tasks=status.getJSONArray("tasks");
                main.post(() -> {
                    if(activity.isFinishing()||activity.isDestroyed())return;
                    sync(activity,tasks);
                    String[] labels=new String[tasks.length()];
                    for(int i=0;i<labels.length;i++)labels[i]=label(activity,tasks.optJSONObject(i))+" · #"+tasks.optJSONObject(i).optInt("task_id");
                    String info=tasks.length()==0?"没有后台应用":status.optInt("width")+" × "+status.optInt("height")+" / "+status.optInt("density_dpi")+" dpi";
                    AlertDialog.Builder dialog=new AlertDialog.Builder(activity).setTitle("后台应用 · "+info).setNegativeButton("关闭",null);
                    if(labels.length>0)dialog.setItems(labels,(d,index) -> restore(activity,tasks.optJSONObject(index),false));
                    else dialog.setMessage("使用后台模式打开应用后，会显示在这里。");
                    dialog.show();
                    if(!cardError.isEmpty())Toast.makeText(activity,cardError,Toast.LENGTH_LONG).show();
                });
            } catch(Exception error) { main.post(() -> Toast.makeText(activity,"读取后台应用失败："+error.getMessage(),Toast.LENGTH_LONG).show()); }
        },"background-list").start();
    }
    static void restore(Activity activity,JSONObject task,boolean removeCard) {
        if(AgentService.running) {
            Toast.makeText(activity,"请先停止当前模型任务，再将应用移回主屏",Toast.LENGTH_LONG).show();
            if(removeCard) { activity.startActivity(new Intent(activity,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); activity.finish(); }
            return;
        }
        new Thread(() -> {
            String error=null;
            try {
                JSONObject result=Wire.call(new JSONObject().put("op","background_restore").put("task_id",task.getInt("task_id")).put("component",task.getString("component")));
                if(!result.optBoolean("ok"))error=result.optString("error","返回失败");
            } catch(Exception e) { error=e.getMessage(); }
            final String failure=error;
            main.post(() -> {
                if(failure!=null)Toast.makeText(activity,"无法返回应用："+failure,Toast.LENGTH_LONG).show();
                if(removeCard)activity.finishAndRemoveTask();
                refresh();
            });
        },"background-restore").start();
    }
}
