package dev.navix.agent;
import org.json.*;
final class TaskPolicy {
    static final String GOAL = "\n当前是 Goal 持续执行模式。持续推进原始目标，不要因一轮回复结束或步骤较多就停止。"
        + "每次操作后根据工具返回重新检查结果；只有任务全部完成并有工具结果作为依据时，调用 complete_task(summary,evidence)。"
        + "普通文字回复不能结束任务。如果上一轮未完成，则从当前状态继续，不重复已成功的发送、提交等操作。遇到工具错误请观察现状并调整方法。缺少必要信息、用户偏好或需用户处理的步骤时调用 ask_user 提出具体问题，可附最多4个选项。工具会等待真实用户回复后返回，不能编造答案、把等待当完成或反复猜测；收到回答后先观察当前页面再继续。";
    static String instructions(boolean goal) { return Protocol.SYSTEM+(goal?GOAL:""); }
    static JSONArray tools(boolean goal) throws Exception {
        JSONArray tools=Protocol.tools();
        if(goal) tools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","complete_task")
            .put("description","仅在原始目标全部完成且有实际工具结果作为依据时调用。提交结果摘要和验证依据。")
            .put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()
                .put("summary",new JSONObject().put("type","string"))
                .put("evidence",new JSONObject().put("type","string")))
                .put("required",new JSONArray().put("summary").put("evidence")).put("additionalProperties",false))));
        if(goal)tools.put(new JSONObject().put("type","function").put("function",new JSONObject().put("name","ask_user")
            .put("description","缺少必要信息时询问用户并等待回复，保留当前任务。问题最多1000字，可附最多4个简短选项；用户也可自由回答或停止任务。")
            .put("parameters",new JSONObject().put("type","object").put("properties",new JSONObject()
                .put("question",new JSONObject().put("type","string"))
                .put("options",new JSONObject().put("type","array").put("maxItems",4).put("items",new JSONObject().put("type","string"))))
                .put("required",new JSONArray().put("question")).put("additionalProperties",false))));
        return tools;
    }
    static JSONObject request(String model,JSONArray messages,String thinking,boolean goal) throws Exception {
        JSONObject q=new JSONObject().put("model",model).put("messages",messages).put("tools",tools(goal)).put("max_tokens",8192).put("stream",false);
        if(thinking.equals("enabled")||thinking.equals("disabled")) q.put("thinking",new JSONObject().put("type",thinking));
        return q;
    }
    static String describe(JSONObject q) {
        String op=q.optString("op");
        switch(op) {
            case "observe":return "读取当前页面";
            case "list_apps":return "查询已安装应用";
            case "launch":case "launch_app":return "打开应用："+q.optString("package");
            case "switch_app":return "切换应用："+q.optString("package");
            case "input_text":return "向当前输入框输入文字";
            case "input_key":return "输入按键："+q.optString("key");
            case "start_intent":return "打开页面："+q.optString("action",q.optString("component",q.optString("data")));
            case "am":return "执行 am："+(q.optJSONArray("args")==null?"":q.optJSONArray("args").optString(0));
            case "click":return "点击控件 "+q.optString("node");
            case "long_click":return "长按控件 "+q.optString("node");
            case "set_text":return "输入文字到控件 "+q.optString("node");
            case "scroll_forward":return "向下滚动";
            case "scroll_backward":return "向上滚动";
            case "home":return "返回桌面";
            case "back":return "返回上一页";
            case "wait":return "等待页面更新";
            case "read_sms":return "读取任务所需短信";
            case "get_phone_numbers":return "读取本机 SIM 号码";
            case "ask_user":return "等待你补充信息";
            case "complete_task":return "核验任务完成情况";
            default:return op;
        }
    }
    static JSONObject completion(JSONObject args,boolean goal,boolean hasEvidence) throws Exception {
        if(!goal)throw new IllegalStateException("Goal mode is not active");
        String summary=args.getString("summary").trim(),evidence=args.getString("evidence").trim();
        if(summary.isEmpty()||evidence.isEmpty()||summary.length()>2000||evidence.length()>4000)throw new IllegalArgumentException("请提供完成摘要和验证依据");
        if(!hasEvidence)throw new IllegalStateException("尚无成功的页面观察或命令输出，请先验证结果");
        return new JSONObject().put("ok",true).put("goalCompleted",true).put("summary",summary).put("evidence",evidence);
    }
}
