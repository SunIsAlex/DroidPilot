package dev.navix.agent;
import org.json.*;
public final class TaskPolicyTest {
    static void expect(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        JSONArray messages=new JSONArray().put(Protocol.message("user","打开设置"));
        expect(!TaskPolicy.request("test",messages,"auto",false).has("thinking"),"Automatic keeps provider default");
        expect(TaskPolicy.request("test",messages,"enabled",true).getJSONObject("thinking").getString("type").equals("enabled"),"Thinking on");
        expect(TaskPolicy.request("test",messages,"disabled",false).getJSONObject("thinking").getString("type").equals("disabled"),"Thinking off");
        expect(TaskPolicy.tools(true).length()==TaskPolicy.tools(false).length()+1,"Goal adds completion tool");
        JSONObject done=new JSONObject().put("summary","已打开设置").put("evidence","最新观察的包名为 com.android.settings");
        try{TaskPolicy.completion(done,true,false);throw new AssertionError("Completion without evidence");}catch(IllegalStateException expected){}
        try{TaskPolicy.completion(done,false,true);throw new AssertionError("Completion outside goal");}catch(IllegalStateException expected){}
        expect(TaskPolicy.completion(done,true,true).getBoolean("goalCompleted"),"Confirmed goal completion");
        expect(!TaskPolicy.describe(new JSONObject().put("op","set_text").put("node","1:2").put("text","private-content")).contains("private-content"),"Toast does not expose typed content");
        expect(TaskPolicy.describe(new JSONObject().put("op","launch_app").put("package","com.android.settings")).contains("com.android.settings"),"Toast identifies target app");
        System.out.println("PASS: thinking on/off/default, goal completion evidence, action toast descriptions");
    }
}
