package dev.navix.agent;
import org.json.*;
import java.util.*;
public final class AmToolsTest {
    static JSONObject call(String name,JSONObject q)throws Exception {
        return Protocol.parseCall(new JSONObject().put("function",new JSONObject().put("name",name).put("arguments",q.toString())));
    }
    static void require(boolean condition,String what) { if(!condition)throw new AssertionError(what); }
    static void reject(String name,JSONObject q)throws Exception {
        try { call(name,q); throw new AssertionError("Accepted invalid "+name); } catch(IllegalArgumentException|JSONException expected) {}
    }
    public static void main(String[] args)throws Exception {
        List<String> launch=AmCommand.arguments(call("launch_app",new JSONObject().put("package","com.android.settings")));
        require(launch.equals(Arrays.asList("start","-W","--user","0","-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER","-p","com.android.settings","-f","0x10000000")),"Direct launch arguments");
        String literal="中文 ; $(id) `id` ' quoted & spaces";
        JSONObject intent=new JSONObject().put("action","android.intent.action.VIEW").put("data","demo://item?q=a&b=2")
            .put("component","dev.navix.agent/.FixtureActivity").put("flags","0x10000000").put("categories",new JSONArray().put("android.intent.category.DEFAULT"))
            .put("extras",new JSONArray().put(new JSONObject().put("key","message").put("type","string").put("value",literal))
                .put(new JSONObject().put("key","enabled").put("type","boolean").put("value","true")));
        List<String> actual=AmCommand.arguments(call("start_intent",intent));
        require(actual.contains(literal)&&actual.contains("demo://item?q=a&b=2")&&actual.contains("--ez"),"Literal extras, URI and type preserved");
        JSONArray raw=new JSONArray().put("broadcast").put("--user").put("0").put("-a").put("test.action").put("--es").put("value").put(literal);
        require(AmCommand.arguments(call("am",new JSONObject().put("args",raw))).get(7).equals(literal),"Raw argv preserved");
        reject("launch_app",new JSONObject().put("package","com.android.settings;id"));
        reject("start_intent",new JSONObject());
        reject("start_intent",new JSONObject().put("action","a").put("extras",new JSONArray().put(new JSONObject().put("key","x").put("type","int").put("value","1;id"))));
        reject("am",new JSONObject().put("args",new JSONArray().put("start -a x")));
        reject("am",new JSONObject().put("args",new JSONArray().put("help").put(1)));
        reject("am",new JSONObject().put("args",new JSONArray().put("help").put("a\0b")));
        JSONArray oversized=new JSONArray(); for(int i=0;i<129;i++)oversized.put("help");
        reject("am",new JSONObject().put("args",oversized));
        JSONObject output=CommandRunner.run(Arrays.asList("python","-c","print('x'*100000)"),3000,100);
        require(output.getBoolean("ok")&&output.getBoolean("truncated")&&output.getString("output").length()==100,"Large output drained and bounded");
        JSONObject failure=CommandRunner.run(Arrays.asList("python","-c","print('Error: unable to resolve Intent')"),3000,1000);
        require(!failure.getBoolean("ok")&&failure.getInt("exitCode")==0,"am semantic errors detected even with exit 0");
        JSONObject timeout=CommandRunner.run(Arrays.asList("python","-c","import time;time.sleep(10)"),150,1000);
        require(timeout.getBoolean("timedOut")&&!timeout.getBoolean("ok"),"Timeout handled");
        JSONObject literalResult=CommandRunner.run(Arrays.asList("python","-c","import sys;print(sys.argv[1],end='')",literal),3000,1000);
        require(literalResult.getString("output").equals(literal),"No shell interpolation");
        System.out.println("PASS: direct launch, Intent/typed extras, raw am argv, validation, output drain, timeout, literal shell characters");
    }
}
