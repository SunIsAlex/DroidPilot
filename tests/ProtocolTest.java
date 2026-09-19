package dev.navix.agent;
import org.json.*;
public final class ProtocolTest {
    private static JSONObject call(String name,JSONObject args)throws Exception{return new JSONObject().put("function",new JSONObject().put("name",name).put("arguments",args.toString()));}
    private static void reject(JSONObject call)throws Exception{try{Protocol.parseCall(call);throw new AssertionError("Accepted invalid tool call");}catch(IllegalArgumentException|JSONException expected){}}
    public static void main(String[] unused)throws Exception{
        JSONObject text=new JSONObject().put("op","set_text").put("node","1:3").put("text","搜索中文 🌏");
        if(!Protocol.parseCall(call("phone",text)).toString().equals(text.toString()))throw new AssertionError("Unicode lost");
        reject(call("shell",new JSONObject().put("op","home")));
        reject(call("phone",new JSONObject().put("op","exec")));
        reject(call("phone",new JSONObject().put("op","click").put("node","com.app:id/button")));
        reject(call("phone",new JSONObject().put("op","click")));
        reject(call("phone",new JSONObject().put("op","launch").put("package","com.app;id")));
        reject(call("phone",new JSONObject().put("op","set_text").put("node","1:2").put("text",new String(new char[2001]))));
        System.out.println("PASS: Unicode, invalid tools, missing/stale-format node, package injection, text limit");
    }
}
