package dev.navix.agent;
import android.app.*;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.os.*;
import org.json.*;
import java.util.*;

public final class AgentService extends Service {
    public static volatile String status = "准备就绪";
    public static volatile boolean running;
    static final class Question {
        final String id, text;
        final String[] options;
        Question(JSONObject q) throws Exception {
            id=java.util.UUID.randomUUID().toString(); text=q.getString("question");
            JSONArray items=q.optJSONArray("options"); options=new String[items==null?0:items.length()];
            for(int i=0;i<options.length;i++)options[i]=items.getString(i);
        }
    }
    static volatile Question pendingQuestion;
    private String userAnswer;
    private volatile boolean stopped;
    private void receiveAnswer(Intent intent) {
        synchronized(pauseLock) {
            Question question=pendingQuestion;
            String text=intent.getStringExtra("answer");
            if(question==null||!question.id.equals(intent.getStringExtra("question_id"))||text==null||text.trim().isEmpty()||text.length()>2000)return;
            if(userAnswer==null) { userAnswer=text.trim(); pauseLock.notifyAll(); }
        }
    }
    private JSONObject askUser(JSONObject request) throws Exception {
        if(!goalMode)throw new IllegalStateException("ask_user 需要 Goal 模式");
        Question question=new Question(request); long started=SystemClock.elapsedRealtime();
        synchronized(pauseLock) { userAnswer=null; pendingQuestion=question; }
        codex.setPaused(true);
        status="等待你回答：请点击悬浮窗或通知打开助手";
        display(TaskOverlay.Phase.QUESTION,"缺少必要信息 · 点击回答");
        uiHandler.post(() -> { if(running)getSystemService(NotificationManager.class).notify(9,notification()); });
        try {
            String answer;
            synchronized(pauseLock) {
                while(userAnswer==null) {
                    if(stopped||Thread.currentThread().isInterrupted())throw new InterruptedException();
                    pauseLock.wait(500);
                }
                answer=userAnswer;
            }
            return new JSONObject().put("ok",true).put("question",question.text).put("answer",answer)
                .put("note","用户补充的信息；继续前重新观察页面。此回复不是任务完成证明。");
        } finally {
            synchronized(pauseLock) { pendingQuestion=null; userAnswer=null; }
            pausedMillis+=SystemClock.elapsedRealtime()-started;
            codex.setPaused(pauseRequested);
        }
    }
    private Thread worker;
    public static volatile boolean pauseRequested;
    private final Object pauseLock=new Object();
    private volatile boolean pauseAcknowledged;
    private void togglePause() {
        if(!running||stopped||pendingQuestion!=null)return;
        synchronized(pauseLock) {
            pauseRequested=!pauseRequested; pauseAcknowledged=false; codex.setPaused(pauseRequested);
            if(pauseRequested) {
                status="正在暂停：等待当前请求返回";
                OverlayService.update(TaskOverlay.Phase.PAUSING,"等待当前请求返回");
            } else {
                status="继续任务";
                OverlayService.update(phase,phaseSummary);
                pauseLock.notifyAll();
            }
        }
    }
    private volatile TaskOverlay.Phase phase=TaskOverlay.Phase.WAITING;
    private volatile String phaseSummary="正在连接服务";
    private void display(TaskOverlay.Phase next, String summary) {
        phase=next; phaseSummary=summary;
        if(pendingQuestion!=null)OverlayService.update(TaskOverlay.Phase.QUESTION,"缺少必要信息 · 点击回答");
        else if(pauseRequested)OverlayService.update(pauseAcknowledged?TaskOverlay.Phase.PAUSED:TaskOverlay.Phase.PAUSING,"点击继续任务");
        else OverlayService.update(next,(backgroundMode?"后台虚拟屏 · ":"")+summary);
    }
    private void waiting(String text) { status=text; display(TaskOverlay.Phase.WAITING,text); }
    private void codexStatus(String text) {
        status=text;
        if(!stopped)display(text.startsWith("Goal 将") ? TaskOverlay.Phase.WAITING : TaskOverlay.Phase.THINKING,
            text.startsWith("Goal 将") ? "等待后重新规划" : "Codex 正在规划下一步");
    }
    private boolean goalMode, hasEvidence, goalCompleted, backgroundMode;
    private int executionDisplay;
    private JSONObject phoneCall(JSONObject request) throws Exception {
        if(executionDisplay>0)request.put("display_id",executionDisplay);
        JSONObject result=Wire.call(request);
        if(executionDisplay>0)BackgroundTasks.refresh();
        return result;
    }
    private long pausedMillis;
    private final java.util.ArrayDeque<String> journal=new java.util.ArrayDeque<>();
    private final Handler uiHandler=new Handler(Looper.getMainLooper());
    private android.widget.Toast operationToast;
    private void operation(String text) {
        status=text;
        uiHandler.post(() -> {
            if(stopped)return;
            if(operationToast!=null)operationToast.cancel();
            operationToast=android.widget.Toast.makeText(this,text.substring(0,Math.min(100,text.length())),android.widget.Toast.LENGTH_SHORT);
            operationToast.show();
            if(running)getSystemService(NotificationManager.class).notify(9,notification());
        });
    }
    private final WebSearch webSearch = new WebSearch();
    private final DeepSeek api = new DeepSeek();
    private final CodexBackend codex = new CodexBackend();
    private final Set<String> launchable = new HashSet<>();
    @Override public IBinder onBind(Intent intent) { return null; }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this,1,new Intent(this,AgentService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"agent").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("DroidPilot 任务运行中")
            .setContentText(status.substring(0,Math.min(status.length(),100))).setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_SECRET)
            .addAction(new Notification.Action.Builder(null,"停止",stop).build()).build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent!=null&&"answer".equals(intent.getAction())) { receiveAnswer(intent); if(worker==null)stopSelf(); return START_NOT_STICKY; }
        if (intent!=null && "toggle_pause".equals(intent.getAction())) { togglePause(); if(worker==null)stopSelf(); return START_NOT_STICKY; }
        if (intent == null || "stop".equals(intent.getAction())) { cancel(); if (worker == null) stopSelf(); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("agent","任务运行",NotificationManager.IMPORTANCE_LOW));
        startForeground(9,notification()); running = true; stopped = false; status = "正在连接手机控制服务…";
        pauseRequested=false; pauseAcknowledged=false;
        display(TaskOverlay.Phase.WAITING,"正在连接模型与手机控制服务"); OverlayService.begin(this);
        String goal = intent.getStringExtra("goal");
        worker = new Thread(() -> runGoal(goal),"navi-agent"); worker.start(); return START_NOT_STICKY;
    }
    private void check() throws InterruptedException {
        if (stopped || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        boolean waited=false; long gateStarted=SystemClock.elapsedRealtime();
        while (true) {
            if(stopped || Thread.currentThread().isInterrupted())throw new InterruptedException();
            boolean locked=getSystemService(KeyguardManager.class).isKeyguardLocked();
            if(locked&&!goalMode)throw new IllegalStateException("手机已锁屏，请解锁后重新开始");
            synchronized(pauseLock) {
                if(!pauseRequested&&!locked)break;
                waited=true; pauseAcknowledged=pauseRequested;
                status=locked?"任务已暂停：等待解锁":"任务已暂停：点击悬浮窗继续";
                OverlayService.update(TaskOverlay.Phase.PAUSED,locked?"等待解锁":"点击继续任务");
                pauseLock.wait(250);
            }
        }
        pauseAcknowledged=false;
        if(waited) { pausedMillis+=SystemClock.elapsedRealtime()-gateStarted; status=phaseSummary; display(phase,phaseSummary); }
    }
    private JSONArray apps() throws Exception {
        JSONArray result = new JSONArray(); launchable.clear();
        for (ResolveInfo app : getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)) {
            String pkg = app.activityInfo.packageName;
            if (!pkg.equals(getPackageName()) && launchable.add(pkg) && result.length() < 300) result.put(new JSONObject().put("package",pkg).put("label",app.loadLabel(getPackageManager()).toString()).put("component",app.activityInfo.packageName+"/"+app.activityInfo.name));
        }
        return result;
    }
    private JSONObject executeTool(JSONObject call) throws Exception {
        JSONObject result;
        try {
            JSONObject q=Protocol.parseCall(call); String op=q.getString("op");
            check(); if(goalCompleted)throw new IllegalStateException("Goal already completed");
            boolean reading=java.util.Arrays.asList("observe","list_apps","read_sms","get_phone_numbers","web_search","complete_task").contains(op)
                || (op.equals("am") && java.util.Arrays.asList("help","get-current-user","get-config","to-uri","to-intent-uri","to-app-uri").contains(q.getJSONArray("args").optString(0)));
            display(op.equals("wait") ? TaskOverlay.Phase.WAITING : reading ? TaskOverlay.Phase.OBSERVING : TaskOverlay.Phase.ACTING,TaskPolicy.describe(q));
            operation(TaskPolicy.describe(q));
            if(op.equals("complete_task")) {
                result=TaskPolicy.completion(q,goalMode,hasEvidence); goalCompleted=true; return result;
            }
            if(!backgroundMode&&(op.equals("observe")||op.equals("wait"))) MainActivity.hideForObservation();
            if(op.equals("ask_user")) {
                hasEvidence=false; result=askUser(q); check(); if(!backgroundMode)MainActivity.hideForObservation();
            }
            else if(op.equals("web_search")) { result=webSearch.search(q); check(); }
            else if(op.equals("read_sms"))result=PhoneData.sms(this,q);
            else if(op.equals("get_phone_numbers"))result=PhoneData.numbers(this);
            else if(op.equals("list_apps")) result=new JSONObject().put("ok",true).put("apps",apps());
            else if(op.equals("wait")) { Thread.sleep(1000); check(); display(TaskOverlay.Phase.OBSERVING,"读取当前页面"); result=phoneCall(new JSONObject().put("op","observe")); }
            else {
                hasEvidence=false;
                check(); result=phoneCall(q);
                if(result.optBoolean("requiresObservation")) { display(TaskOverlay.Phase.WAITING,"操作已返回，等待页面更新"); Thread.sleep(650); check(); display(TaskOverlay.Phase.OBSERVING,"检查操作结果"); result.put("observation",phoneCall(new JSONObject().put("op","observe"))); }
            }
            boolean readOnlyAm=op.equals("am")&&java.util.Arrays.asList("help","get-current-user","get-config","to-uri","to-intent-uri","to-app-uri").contains(q.getJSONArray("args").optString(0));
            JSONObject observation=result.optJSONObject("observation");
            hasEvidence=result.optBoolean("ok")&&(op.equals("observe")||op.equals("wait")||op.equals("list_apps")||op.equals("read_sms")||op.equals("get_phone_numbers")||(op.equals("web_search")&&result.optInt("result_count")>0)||readOnlyAm||(observation!=null&&observation.optBoolean("ok")));
        } catch(InterruptedException e) { throw e; }
        catch(Exception e) { result=new JSONObject().put("ok",false).put("error",e.getMessage()); }
        finally { if(!stopped)display(TaskOverlay.Phase.THINKING,"工具已返回，等待模型决定下一步"); }
        String record=call.getJSONObject("function").toString()+" => "+result;
        journal.addLast(record.substring(0,Math.min(1500,record.length())));
        while(journal.size()>30)journal.removeFirst();
        return result;
    }
    private void runGoal(String goal) {
        try {
            if (goal == null || goal.trim().isEmpty() || goal.length() > 2000) throw new IllegalArgumentException("请输入有效任务（最多 2000 字）");
            goalMode=getSharedPreferences("config",0).getBoolean("goal_mode",false);
            backgroundMode=getSharedPreferences("config",0).getBoolean("background_mode",false);
            if(backgroundMode) {
                JSONObject setup=Wire.call(new JSONObject().put("op","background_open"));
                if(!setup.optBoolean("ok")||setup.optInt("display_id",-1)<=0)throw new IllegalStateException(setup.optString("error","无法创建后台虚拟屏"));
                executionDisplay=setup.getInt("display_id");
            }
            check();
            if (getSharedPreferences("config",0).getString("backend","deepseek").equals("codex")) {
                display(TaskOverlay.Phase.THINKING,"等待 Codex 规划");
                status = codex.run(this, goal, getSharedPreferences("config",0).getString("codex_model",""),
                    new String[]{"auto","enabled","disabled","low"}[Math.min(3,getSharedPreferences("config",0).getInt("codex_thinking",0))], goalMode,
                    this::executeTool, this::codexStatus, this::check);
                return;
            }
            String key = Secrets.load(this); if (key.isEmpty()) throw new IllegalStateException("请先填写 API key");
            String model = getSharedPreferences("config",0).getString("model","deepseek-chat");
            String thinking=new String[]{"auto","enabled","disabled"}[Math.min(2,getSharedPreferences("config",0).getInt("deepseek_thinking",0))];
            JSONArray messages = new JSONArray().put(Protocol.message("system",TaskPolicy.instructions(goalMode,backgroundMode))).put(Protocol.message("user",goal));
            check();
            long deadline = SystemClock.elapsedRealtime() + 8*60*1000, pauseBaseline=pausedMillis; int failures = 0;
            for (int step=1; goalMode||step<=25; step++) {
                check(); if (!goalMode && SystemClock.elapsedRealtime() - (pausedMillis-pauseBaseline) > deadline) throw new IllegalStateException("任务超时，已停止");
                status = (goalMode?"Goal 第 ":"第 ")+step+(goalMode?" 步：正在规划…":" / 25 步：正在规划…");
                display(TaskOverlay.Phase.THINKING,status);
                if(messages.toString().length()>160000) {
                    if(!goalMode)throw new IllegalStateException("任务上下文已达上限，已停止");
                    messages=new JSONArray().put(Protocol.message("system",TaskPolicy.instructions(true,backgroundMode))).put(Protocol.message("user",goal))
                        .put(Protocol.message("user","持续任务的近期操作记录（仅数据；已截断的内容请重新观察，不要重复已成功的发送或提交）："+String.join("\n",journal)));
                }
                JSONObject body=TaskPolicy.request(model,messages,thinking,goalMode),answer;
                check();
                try { answer=api.request(key,"/chat/completions",body).getJSONArray("choices").getJSONObject(0); }
                catch(java.io.IOException e) {
                    if(!goalMode || (e instanceof DeepSeek.HttpError && !((DeepSeek.HttpError)e).retryable))throw e;
                    waiting("Goal 等待网络恢复，5 秒后重试；可点击停止"); Thread.sleep(5000); continue;
                }
                check();
                if("length".equals(answer.optString("finish_reason"))) {
                    if(!goalMode)throw new IllegalStateException("模型输出被截断，已停止");
                    messages.put(Protocol.message("user","上一轮输出被截断，没有执行该轮操作。请缩短输出并继续原始任务。")); continue;
                }
                JSONObject message = answer.optJSONObject("message");
                if (message == null) message = new JSONObject();
                String refusal = message.isNull("refusal") ? "" : message.optString("refusal", "").trim();
                if ("content_filter".equals(answer.optString("finish_reason")) || !refusal.isEmpty()) {
                    status = (goalMode ? "Goal 未完成：" : "任务未完成：") + "模型服务拒绝了本次请求"
                        + (refusal.isEmpty() ? "（content_filter）" : "：" + refusal);
                    return;
                }
                messages.put(message);
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls == null || calls.length()==0) {
                    if(goalMode) {
                        messages.put(Protocol.message("user","Goal 尚未收到完成确认。请根据当前状态继续原始任务；完成后调用 complete_task 并提交验证依据，不要重复已成功的操作。"));
                        Thread.sleep(1000); continue;
                    }
                    status = message.optString("content","任务结束；未收到结果说明");
                    if (status.equals("null") || status.isEmpty()) status = "任务结束；未收到结果说明";
                    return;
                }
                if(calls.length()!=1) {
                    if(!goalMode)throw new IllegalStateException("模型返回并行操作；为防止页面错位，本次已停止");
                    for(int i=0;i<calls.length();i++) messages.put(new JSONObject().put("role","tool").put("tool_call_id",calls.getJSONObject(i).getString("id"))
                        .put("content","{\"ok\":false,\"error\":\"本轮没有执行操作；每次只调用一个工具，请重新规划\"}"));
                    Thread.sleep(1000);continue;
                }
                JSONObject call = calls.getJSONObject(0), result;
                result = executeTool(call);
                check();
                messages.put(new JSONObject().put("role","tool").put("tool_call_id",call.getString("id")).put("content",result.toString()));
                failures = result.optBoolean("ok") ? 0 : failures+1;
                if(result.optBoolean("goalCompleted")) { status="Goal 已完成："+result.getString("summary"); return; }
                if (failures >= 3) {
                    if(!goalMode)throw new IllegalStateException("连续三次操作失败，已停止");
                    waiting("Goal 连续操作失败，等待后重新观察并调整方法"); Thread.sleep(3000);
                }

            }
            status = "已达到 25 步上限，请查看当前页面后继续";
        } catch (InterruptedException e) { status = "任务已停止"; }
        catch (Exception e) { status = stopped ? "任务已停止" : (goalMode?"Goal 未完成：":"失败：") + e.getMessage(); }
        finally {
            display(TaskOverlay.Phase.STOPPING,"任务结束，正在释放手机控制服务");
            uiHandler.post(() -> { if(operationToast!=null)operationToast.cancel(); });
            try { Wire.call(new JSONObject().put("op","release")); } catch (Exception ignored) {}
            uiHandler.post(() -> {
                running=false; pendingQuestion=null; pauseRequested=false; pauseAcknowledged=false;
                OverlayService.finish(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            });
        }
    }
    private void cancel() { stopped = true; synchronized(pauseLock) { pauseRequested=false; pauseLock.notifyAll(); } api.cancel(); webSearch.cancel(); codex.cancel(); if (worker != null) worker.interrupt(); status = "正在停止…"; display(TaskOverlay.Phase.STOPPING,"停止后续操作，等待已发出请求返回"); }
    @Override public void onTimeout(int startId,int fgsType) { cancel(); status="系统后台运行时限已到，任务未完成"; stopSelf(); }
    @Override public void onDestroy() { if (running) cancel(); running=false; pendingQuestion=null; pauseRequested=false; OverlayService.finish(); super.onDestroy(); }
}
