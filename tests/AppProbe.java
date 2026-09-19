package dev.navix.agent;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
/** Runs under the actual companion app UID / SELinux context. */
public final class AppProbe extends Instrumentation {
    private boolean live, goal, deepseek;
    @Override public void onCreate(Bundle args) { super.onCreate(args); live="true".equals(args.getString("live")); goal="true".equals(args.getString("goal")); deepseek="true".equals(args.getString("deepseek")); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            JSONObject ping = Wire.call(new JSONObject().put("op","ping"));
            if(!ping.optBoolean("ok") || ping.getInt("uid")!=0) throw new AssertionError(ping.toString());
            String deepseekResult="";
            if(deepseek) {
                String key=Secrets.load(getTargetContext());
                if(key.isEmpty())deepseekResult="SKIP: DeepSeek API key not configured\n";
                else {
                    String model=getTargetContext().getSharedPreferences("config",0).getString("model","deepseek-chat");
                    for(String thinking:new String[]{"enabled","disabled"}) {
                        org.json.JSONArray messages=new org.json.JSONArray().put(Protocol.message("system",Protocol.SYSTEM))
                            .put(Protocol.message("user","这是一项连接测试。请只返回 am 工具调用，args 为 [\"help\"]。不要调用其它工具。"));
                        JSONObject answer=new DeepSeek().request(key,"/chat/completions",TaskPolicy.request(model,messages,thinking,false));
                        if(answer.getJSONArray("choices").length()==0)throw new AssertionError("Missing DeepSeek response");
                    }
                    deepseekResult="PASS: live DeepSeek thinking enabled/disabled requests accepted\n";
                }
            }
            String codex = new CodexBackend().checkConnection(getTargetContext());
            if (!codex.contains("登录有效")) throw new AssertionError(codex);
            if (live) {
                final int[] calls={0};
                String text=new CodexBackend().run(getTargetContext(),
                    "仅调用一次 am 工具，args 为 [\"help\"]；确认返回包含 Activity manager 后"+(goal?"调用 complete_task 提交完成摘要与依据。":"回复连接成功。")+"不要读屏或执行其它动作。", "", "low", goal,
                    call -> {
                        JSONObject q=Protocol.parseCall(call);
                        if(q.getString("op").equals("complete_task"))return TaskPolicy.completion(q,goal,calls[0]>0);
                        if (!q.getString("op").equals("am") || !q.getJSONArray("args").toString().equals("[\"help\"]")) throw new SecurityException("Test permits am help only");
                        JSONObject reply=Wire.call(q);
                        if (!reply.optBoolean("ok") || !reply.optString("output").contains("Activity manager")) throw new IllegalStateException("Root am help failed");
                        calls[0]++; return reply;
                    }, status -> {}, () -> {});
                if (calls[0]!=1 || text.isEmpty()) throw new AssertionError("Live Codex round trip failed");
            }
            result.putString("stream", deepseekResult + (live?"PASS: real App -> Root relay -> Codex -> Root am(help) -> model completion\n":"") + "PASS: Codex bridge connected from real app UID; login valid\nPASS: companion UID " + android.os.Process.myUid() + " authenticated to root socket\n");
            finish(-1,result);
        } catch(Throwable e) { result.putString("stream", "FAIL: " + e + "\n"); finish(0,result); }
    }
}
