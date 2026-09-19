package dev.navix.agent;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;

/** Owns the overlay independently of a model task. No task is restarted here. */
public final class OverlayService extends Service {
    private static OverlayService instance;
    private static TaskOverlay.Phase latest=TaskOverlay.Phase.FINISHED;
    private static String summary="点击打开 DroidPilot";
    private static final Handler main=new Handler(Looper.getMainLooper());
    private TaskOverlay overlay;
    private boolean opening;
    private int clickCount;
    private volatile String openStage="idle";
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter writer,String[] args) {
        writer.println("phase="+latest+" running="+AgentService.running+" clicks="+clickCount+" opening="+opening+" stage="+openStage);
        if(overlay!=null)writer.println(overlay.diagnostics());
    }
    private SharedPreferences.OnSharedPreferenceChangeListener preferences;
    static void begin(Context context) {
        if(!context.getSharedPreferences("config",0).getBoolean("task_overlay",true))return;
        if(!Settings.canDrawOverlays(context)) {
            main.post(() -> android.widget.Toast.makeText(context,"请在助手中开启悬浮窗权限",android.widget.Toast.LENGTH_LONG).show()); return;
        }
        context.startForegroundService(new Intent(context,OverlayService.class));
    }
    static void update(TaskOverlay.Phase phase,String text) {
        main.post(() -> { latest=phase; summary=text; if(instance!=null)instance.render(); });
    }
    static void finish() { update(TaskOverlay.Phase.FINISHED,"点击查看结果 / 打开 DroidPilot"); }
    @Override public void onCreate() {
        super.onCreate(); instance=this;
        overlay=new TaskOverlay(this,() -> {
            clickCount++;
            if(AgentService.pendingQuestion==null&&latest!=TaskOverlay.Phase.FINISHED&&AgentService.running)startService(new Intent(this,AgentService.class).setAction("toggle_pause"));
            else openAssistant();
        });
        preferences=(prefs,key) -> { if("task_overlay".equals(key)&&!prefs.getBoolean(key,true))stopSelf(); };
        getSharedPreferences("config",0).registerOnSharedPreferenceChangeListener(preferences);
    }
    private void openAssistant() {
        if(opening)return;
        opening=true; openStage="root_pending";
        // Use the authenticated root bridge: some ROMs silently block Service.startActivity.
        new Thread(() -> {
            boolean launched=false;
            try {
                org.json.JSONObject result=Wire.call(new org.json.JSONObject().put("op","start_intent")
                    .put("component","dev.navix.agent/.MainActivity")
                    .put("action",Intent.ACTION_MAIN).put("categories",new org.json.JSONArray().put(Intent.CATEGORY_LAUNCHER))
                    .put("flags","0x10020000"));
                launched=result.optBoolean("ok"); openStage="root_returned_"+launched;
            } catch(Exception error) { openStage="root_error_"+error.getClass().getSimpleName(); }
            final boolean rootLaunched=launched;
            main.post(() -> {
                opening=false;
                if(rootLaunched)return;
                try { startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
                catch(RuntimeException ignored) { }
                android.widget.Toast.makeText(this,"Root 跳转不可用；若未打开，请点击 DroidPilot 悬浮窗通知进入助手",android.widget.Toast.LENGTH_LONG).show();
            });
        },"navi-open-assistant").start();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration); if(overlay!=null)overlay.reposition();
    }
    private void render() { overlay.show(latest,summary); }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null||"dismiss".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("overlay","悬浮任务入口",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,20,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent close=PendingIntent.getService(this,21,new Intent(this,OverlayService.class).setAction("dismiss"),PendingIntent.FLAG_IMMUTABLE);
        startForeground(10,new Notification.Builder(this,"overlay").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("DroidPilot 悬浮窗").setContentText("运行时点击暂停/继续，结束后点击打开助手")
            .setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(new Notification.Action.Builder(null,"关闭悬浮窗",close).build()).build());
        if(!getSharedPreferences("config",0).getBoolean("task_overlay",true)||!Settings.canDrawOverlays(this))stopSelf();
        else render();
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if(instance==this)instance=null;
        if(overlay!=null)overlay.close();
        getSharedPreferences("config",0).unregisterOnSharedPreferenceChangeListener(preferences);
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
}
