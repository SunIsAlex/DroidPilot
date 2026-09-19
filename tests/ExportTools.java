package dev.navix.agent;
import org.json.JSONObject;
public final class ExportTools {
    public static void main(String[] args) throws Exception {
        System.out.println(new JSONObject().put("instructions",Protocol.SYSTEM).put("tools",Protocol.tools()));
    }
}
