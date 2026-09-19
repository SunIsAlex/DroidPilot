package dev.navix.agent;

import org.json.*;
import java.util.*;

/** Converts structured tools into argv. Values are never interpreted by a shell. */
final class AmCommand {
    static String string(JSONObject q, String key) throws Exception {
        Object value = q.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException(key + " must be a string");
        String s = (String)value;
        if (s.indexOf('\0') >= 0 || s.length() > 8192) throw new IllegalArgumentException("Invalid " + key);
        return s;
    }
    static List<String> arguments(JSONObject q) throws Exception {
        List<String> args = new ArrayList<>();
        switch (q.getString("op")) {
            case "launch":
            case "launch_app": {
                String pkg = string(q,"package");
                if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("Invalid package");
                Collections.addAll(args,"start","-W","--user","0","-a","android.intent.action.MAIN","-c","android.intent.category.LAUNCHER","-p",pkg,"-f","0x10000000");
                break;
            }
            case "start_intent": {
                Collections.addAll(args,"start","-W","--user","0");
                boolean target = false;
                String[][] fields = {{"action","-a"},{"data","-d"},{"mime_type","-t"},{"package","-p"},{"component","-n"},{"flags","-f"}};
                for (String[] field : fields) if (q.has(field[0])) {
                    String v = string(q,field[0]);
                    if (v.isEmpty()) throw new IllegalArgumentException("Empty " + field[0]);
                    args.add(field[1]); args.add(v);
                    if (!field[0].equals("flags") && !field[0].equals("mime_type")) target = true;
                }
                if (!target) throw new IllegalArgumentException("Intent needs action, data, package or component");
                if (q.has("categories")) {
                    JSONArray categories = q.getJSONArray("categories");
                    for (int i=0;i<categories.length();i++) { args.add("-c"); args.add(arrayString(categories,i)); }
                }
                if (q.has("extras")) {
                    JSONArray extras = q.getJSONArray("extras");
                    for (int i=0;i<extras.length();i++) {
                        JSONObject extra = extras.getJSONObject(i);
                        String type = string(extra,"type"), value = string(extra,"value");
                        String flag;
                        switch (type) {
                            case "string": flag="--es"; break;
                            case "boolean": flag="--ez"; if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid boolean"); break;
                            case "int": flag="--ei"; Integer.parseInt(value); break;
                            case "long": flag="--el"; Long.parseLong(value); break;
                            case "float": flag="--ef"; if (!Float.isFinite(Float.parseFloat(value))) throw new IllegalArgumentException("Invalid float"); break;
                            case "uri": flag="--eu"; break;
                            case "component": flag="--ecn"; break;
                            default: throw new IllegalArgumentException("Unsupported extra type");
                        }
                        Collections.addAll(args,flag,string(extra,"key"),value);
                    }
                }
                break;
            }
            case "am": {
                JSONArray raw = q.getJSONArray("args");
                for (int i=0;i<raw.length();i++) args.add(arrayString(raw,i));
                if (args.isEmpty() || !args.get(0).matches("[a-z][a-z0-9-]*")) throw new IllegalArgumentException("args must begin with am subcommand, e.g. start or help");
                break;
            }
            default: throw new IllegalArgumentException("Unknown am operation");
        }
        int size=0; for (String arg:args) { if(arg.indexOf('\0')>=0 || arg.length()>8192) throw new IllegalArgumentException("Invalid argument"); size+=arg.length(); }
        if (args.size()>128 || size>16384) throw new IllegalArgumentException("Too many arguments");
        return args;
    }
    private static String arrayString(JSONArray a,int i) throws Exception {
        Object v=a.get(i); if (!(v instanceof String)) throw new IllegalArgumentException("Arguments must be strings"); return (String)v;
    }
}
