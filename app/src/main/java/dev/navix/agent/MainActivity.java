package dev.navix.agent;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.os.*;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.widget.*;
import org.json.*;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private static java.lang.ref.WeakReference<MainActivity> foreground = new java.lang.ref.WeakReference<>(null);
    static void hideForObservation() throws InterruptedException {
        java.util.concurrent.CountDownLatch hidden = new java.util.concurrent.CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            MainActivity activity = foreground.get();
            if (activity != null) activity.moveTaskToBack(true);
            hidden.countDown();
        });
        if (!hidden.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("助手窗口暂时无法隐藏");
        Thread.sleep(350);
    }
    private EditText key, model, goal, codexModel;
    private Spinner backend, deepseekThinking, codexThinking;
    private Switch goalMode, taskOverlay, backgroundMode;
    private LinearLayout deepseekFields, codexFields;
    private TextView state, connection, questionText;
    private LinearLayout questionCard, questionOptions;
    private EditText answerInput;
    private String displayedQuestion;
    private void refreshQuestion() {
        if(questionCard==null)return;
        AgentService.Question question=AgentService.pendingQuestion;
        questionCard.setVisibility(question==null?android.view.View.GONE:android.view.View.VISIBLE);
        if(question==null) { displayedQuestion=null; return; }
        if(question.id.equals(displayedQuestion))return;
        displayedQuestion=question.id; questionText.setText(question.text); answerInput.setText(""); questionOptions.removeAllViews();
        for(String option:question.options) {
            Button button=new Button(this); button.setText(option); button.setAllCaps(false);
            button.setOnClickListener(view -> answerInput.setText(option)); questionOptions.addView(button);
        }
        questionCard.post(() -> questionCard.requestRectangleOnScreen(new android.graphics.Rect(0,0,questionCard.getWidth(),questionCard.getHeight()),false));
    }
    private void submitAnswer() {
        String answer=answerInput.getText().toString().trim();
        if(displayedQuestion==null||answer.isEmpty()||answer.length()>2000) { toast("请填写回答，最多2000字"); return; }
        startService(new Intent(this,AgentService.class).setAction("answer").putExtra("question_id",displayedQuestion).putExtra("answer",answer));
        toast("回答已提交，任务将继续");
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() { public void run() { state.setText(AgentService.status); refreshQuestion(); handler.postDelayed(this,1000); } };
    private LinearLayout column;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        ScrollView scroll = new ScrollView(this); column = new LinearLayout(this); column.setOrientation(1); column.setPadding(36,64,36,36); scroll.addView(column); setContentView(scroll);
        TextView title = label("DroidPilot",30); title.setTextColor(Color.rgb(34,72,119));
        label("语音下达任务 · Root 语义控件操作",16);
        connection = label("正在检测 Root 模块…",14);
        label("Provider（决策后端）",16);
        backend = new Spinner(this);
        backend.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"DeepSeek API","Codex Termux"}));
        column.addView(backend);
        backend.setSelection(getSharedPreferences("config",0).getString("backend","deepseek").equals("codex")?1:0);
        LinearLayout form=column;
        codexFields=new LinearLayout(this); codexFields.setOrientation(1); form.addView(codexFields); column=codexFields;
        label("Codex 模型（可留空使用本机默认）",16); codexModel=field("留空使用 Codex 默认模型",false);
        codexModel.setText(getSharedPreferences("config",0).getString("codex_model",""));
        label("思考模式",16); codexThinking=choice(new String[]{"自动","开启","关闭（模型需支持 none）","轻量（low）"},getSharedPreferences("config",0).getInt("codex_thinking",0));
        label("部分 Codex 模型不支持完全关闭，可选择轻量。",14);
        button("检测 Codex 连接",() -> new Thread(() -> {
            try { String result=new CodexBackend().checkConnection(this); runOnUiThread(() -> toast(result)); }
            catch(Exception e) { runOnUiThread(() -> toast("Codex 连接失败：请先在 Termux 启动 codex-bridge/start.sh。")); }
        }).start());
        deepseekFields=new LinearLayout(this); deepseekFields.setOrientation(1); form.addView(deepseekFields); column=deepseekFields;
        label("DeepSeek API key",16); key = field("留空保留已保存密钥",true);
        key.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        key.setSaveEnabled(false);
        label("模型名称",16); model = field("deepseek-chat",false);
        model.setText(getSharedPreferences("config",0).getString("model","deepseek-chat"));
        label("思考模式",16); deepseekThinking=choice(new String[]{"自动","开启","关闭"},getSharedPreferences("config",0).getInt("deepseek_thinking",0));
        button("检测 API / 选择可用模型",this::models);
        column=form;
        backend.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent,android.view.View view,int position,long id) { providerChanged(position); }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        providerChanged(backend.getSelectedItemPosition());
        goalMode=new Switch(this); goalMode.setText("Goal 模式：持续执行直到完成");
        goalMode.setChecked(getSharedPreferences("config",0).getBoolean("goal_mode",false)); column.addView(goalMode);
        label("Goal 模式不受 25 步 / 8 分钟限制，可能持续消耗额度；可随时停止。锁屏时暂停。",14);
        backgroundMode=new Switch(this); backgroundMode.setText("后台虚拟屏（实验性）");
        backgroundMode.setChecked(getSharedPreferences("config",0).getBoolean("background_mode",false)); column.addView(backgroundMode);
        label("AI 在隐藏的独立屏幕操作目标 App，主屏可继续使用。部分 App 不支持；后台输入仅支持标准文本控件，不切换主屏键盘。",14);
        button("关闭后台虚拟屏 / 将应用移回主屏",() -> {
            if(AgentService.running) { toast("请先停止当前任务"); return; }
            new Thread(() -> {
                try { JSONObject result=Wire.call(new JSONObject().put("op","background_close")); runOnUiThread(() -> toast(result.optBoolean("ok")?"后台虚拟屏已关闭，系统将处理应用任务迁移":"关闭失败："+result.optString("error"))); }
                catch(Exception error) { runOnUiThread(() -> toast("无法连接 Root 服务")); }
            }).start();
        });
        taskOverlay=new Switch(this); taskOverlay.setText("任务悬浮状态条");
        taskOverlay.setChecked(getSharedPreferences("config",0).getBoolean("task_overlay",true)); column.addView(taskOverlay);
        label("显示思考、操作、读取和等待状态；运行时点击暂停/继续，结束后半透明保留，点击打开助手。",14);
        button("悬浮窗权限设置",() -> {
            try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,android.net.Uri.parse("package:"+getPackageName()))); }
            catch(ActivityNotFoundException e) { toast("请在系统设置中开启 DroidPilot 悬浮窗权限"); }
        });
        button("保存配置",() -> { if (save()) toast("配置已保存"); });
        label("任务运行时，页面文字和控件信息会发送给所选后端。Codex 复用 Termux 的登录，无需在此填写密钥。语音由系统识别服务处理。",14);
        button("短信 / 号码权限设置",() -> requestPermissions(new String[]{"android.permission.READ_SMS","android.permission.READ_PHONE_NUMBERS","android.permission.READ_PHONE_STATE"},4));
        label("任务需要时，读取的短信和本机号码会发送给所选模型。仅提供读取工具，不发送或删除短信。",14);
        LinearLayout mainColumn=column;
        questionCard=new LinearLayout(this); questionCard.setOrientation(1); mainColumn.addView(questionCard); column=questionCard;
        label("模型需要你补充信息",20); questionText=label("",16);
        questionOptions=new LinearLayout(this); questionOptions.setOrientation(1); column.addView(questionOptions);
        answerInput=field("输入回答，或选择上方选项",false); answerInput.setSingleLine(false); answerInput.setMaxLines(4); answerInput.setSaveEnabled(false);
        button("提交回答并继续",this::submitAnswer); column=mainColumn; questionCard.setVisibility(android.view.View.GONE);
        label("你想让手机做什么？",20); goal = field("例如：打开设置，找到显示设置",false); goal.setSingleLine(false); goal.setMinLines(2); goal.setMaxLines(5);
        button("🎙 说出指令",this::voice);
        button("开始执行",this::start);
        button("暂停 / 继续任务",() -> { if(AgentService.running)startService(new Intent(this,AgentService.class).setAction("toggle_pause")); else toast("当前没有运行中的任务"); });
        button("停止任务",() -> startService(new Intent(this,AgentService.class).setAction("stop")));
        state = label(AgentService.status,16); state.setTextIsSelectable(true);
        button("重新检测 Root 模块",this::probe);
        button("打开控件测试页",() -> startActivity(new Intent(this,FixtureActivity.class)));
        label("使用方法：先打开目标应用，再打开此助手输入指令。启动 App 时直接跳转；操作当前页面时助手退到后台。也可以直接让它打开某个应用。首版不支持持续语音唤醒。",14);
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=0) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},2);
        probe();
    }
    private Spinner choice(String[] labels,int selected) {
        Spinner s=new Spinner(this); s.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,labels));
        column.addView(s); s.setSelection(Math.max(0,Math.min(selected,labels.length-1))); return s;
    }
    private void providerChanged(int position) {
        deepseekFields.setVisibility(position==0?android.view.View.VISIBLE:android.view.View.GONE);
        codexFields.setVisibility(position==1?android.view.View.VISIBLE:android.view.View.GONE);
    }
    private TextView label(String text,int size) { TextView v=new TextView(this); v.setText(text); v.setTextSize(size); v.setPadding(0,16,0,12); column.addView(v); return v; }
    private EditText field(String hint,boolean secret) { EditText v=new EditText(this); v.setHint(hint); v.setSingleLine(true); if (secret) v.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); column.addView(v); return v; }
    private void button(String text,Runnable action) { Button b=new Button(this); b.setText(text); b.setAllCaps(false); column.addView(b); b.setOnClickListener(v -> action.run()); }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    private boolean save() {
        try {
            boolean useCodex=backend.getSelectedItemPosition()==1;
            android.content.SharedPreferences.Editor config=getSharedPreferences("config",0).edit()
                .putString("backend",useCodex?"codex":"deepseek").putBoolean("goal_mode",goalMode.isChecked()).putBoolean("task_overlay",taskOverlay.isChecked()).putBoolean("background_mode",backgroundMode.isChecked());
            if (useCodex) {
                config.putString("codex_model",codexModel.getText().toString().trim()).putInt("codex_thinking",codexThinking.getSelectedItemPosition());
            } else {
                String token=key.getText().toString().trim();
                if (!token.isEmpty()) {
                    if (!token.matches("[A-Za-z0-9_-]{10,512}")) throw new IllegalArgumentException("密钥格式不正确，请仅粘贴 API key");
                    Secrets.save(this,token); key.setText("");
                }
                String name=model.getText().toString().trim();
                if (name.isEmpty()) throw new IllegalArgumentException("请填写模型名称");
                config.putString("model",name).putInt("deepseek_thinking",deepseekThinking.getSelectedItemPosition());
            }
            config.apply(); return true;
        } catch(Exception e) { toast("保存失败："+e.getMessage()); return false; }
    }
    private void probe() {
        new Thread(() -> {
            String text;
            try { JSONObject r=Wire.call(new JSONObject().put("op","ping")); text=r.optBoolean("ok") ? "✓ KernelSU Root 服务已连接" : "模块拒绝连接"; }
            catch(Exception e) { text="Root 模块未运行：请安装模块并启动，或重启手机"; }
            String result=text; runOnUiThread(() -> connection.setText(result));
        }).start();
    }
    private void models() {
        if (!save()) return;
        new Thread(() -> {
            try {
                String token=Secrets.load(this); if(token.isEmpty()) throw new IllegalArgumentException("请先填写 API key");
                JSONArray data=new DeepSeek().request(token,"/models",null).getJSONArray("data");
                String[] names=new String[data.length()]; for(int i=0;i<names.length;i++) names[i]=data.getJSONObject(i).getString("id");
                runOnUiThread(() -> { if(!isFinishing()&&!isDestroyed()) new AlertDialog.Builder(this).setTitle("选择可用模型").setItems(names,(d,index) -> { model.setText(names[index]); save(); }).show(); });
            } catch(Exception e) { runOnUiThread(() -> toast(e.getMessage())); }
        }).start();
    }
    private void voice() {
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"zh-CN").putExtra(RecognizerIntent.EXTRA_PROMPT,"说出你想完成的任务");
        try { startActivityForResult(i,3); } catch(ActivityNotFoundException e) { toast("系统未提供语音输入界面，请先使用文字输入"); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==3&&result==RESULT_OK&&data!=null) { ArrayList<String> matches=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS); if(matches!=null&&!matches.isEmpty()) { goal.setText(matches.get(0)); toast("指令已识别，点击开始执行"); } }
    }
    private void start() {
        if(AgentService.running) { toast("任务正在运行"); return; }
        if(!save()) return;
        String text=goal.getText().toString().trim(); if(text.isEmpty()) { toast("请先输入或说出指令"); return; }
        try { if(backend.getSelectedItemPosition()==0 && Secrets.load(this).isEmpty()) { toast("请先填写 API key"); return; } } catch(Exception e) { toast("无法读取密钥，请重新保存"); return; }
        startForegroundService(new Intent(this,AgentService.class).putExtra("goal",text)); toast("正在执行；打开 App 时会直接跳转");
    }
    @Override protected void onResume() { super.onResume(); foreground = new java.lang.ref.WeakReference<>(this); handler.post(ticker); }
    @Override protected void onPause() { foreground.clear(); handler.removeCallbacks(ticker); super.onPause(); }
}
