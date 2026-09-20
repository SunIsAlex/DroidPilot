package dev.navix.agent;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.app.UiAutomationConnection;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.util.*;

/** Runs only through app_process under KernelSU. No arbitrary shell tool is exposed. */
public final class RootDaemon {
    private UiAutomation ui;
    private final BackgroundDisplay background=new BackgroundDisplay();
    private int requestedDisplay, capturedDisplay;
    private final HandlerThread thread = new HandlerThread("navi-ui");
    private final Map<String, AccessibilityNodeInfo> nodes = new HashMap<>();
    private final Map<String, String> signatures = new HashMap<>();
    private int generation, visited;
    private long captured, lastRequest;
    private String activePackage = "";
    private int activeWindow;
    private final int appUid;
    RootDaemon(int uid) { appUid = uid; thread.start(); }
    public static void main(String[] args) throws Exception {
        if (android.os.Process.myUid() != 0) throw new SecurityException("Requires root");
        Looper.prepareMainLooper();
        RootDaemon daemon = new RootDaemon(Integer.parseInt(args[0]));
        new Thread(() -> { try { daemon.serve(); } catch (Exception e) { e.printStackTrace(); System.exit(1); } }, "navi-socket").start();
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(new Runnable() { public void run() {
            synchronized (daemon) {
                if (daemon.ui != null && SystemClock.elapsedRealtime() - daemon.lastRequest > 90000) {
                    try { daemon.execute(new JSONObject().put("op", "release")); } catch (Exception ignored) {}
                }
            }
            handler.postDelayed(this, 30000);
        } }, 30000);
        Looper.loop();
    }
    private void serve() throws Exception {
        try (LocalServerSocket server = new LocalServerSocket(Wire.SOCKET)) {
            System.out.println("DroidPilot root daemon ready; allowed UID=" + appUid);
            while (true) {
                LocalSocket client = server.accept(); boolean transferred = false;
                try {
                    client.setSoTimeout(15000);
                    int uid = client.getPeerCredentials().getUid();
                    if (uid != 0 && uid != appUid) {
                        Wire.write(client.getOutputStream(), new JSONObject().put("ok", false).put("error", "unauthorized"));
                        continue;
                    }
                    JSONObject request = Wire.read(client.getInputStream());
                    if (request.optString("op").equals("codex_bridge")) {
                        JSONObject payload = request.getJSONObject("request");
                        Thread relay = new Thread(() -> relayCodex(client, payload), "navi-codex-relay");
                        relay.setDaemon(true); relay.start(); transferred = true;
                        continue;
                    }
                    JSONObject response;
                    try { response = execute(request); }
                    catch (Exception e) { response = new JSONObject().put("ok", false).put("error", e.getClass().getSimpleName() + ": " + e.getMessage()); }
                    Wire.write(client.getOutputStream(), response);
                } catch (Exception e) { System.err.println(e.getClass().getSimpleName()); }
                finally { if (!transferred) client.close(); }
            }
        }
    }
    // Android SELinux can block app-to-Termux sockets. Relay through the existing
    // authenticated Root endpoint; Codex itself still runs as the Termux UID.
    private void relayCodex(LocalSocket client, JSONObject request) {
        try (LocalSocket bridge = new LocalSocket()) {
            bridge.connect(new android.net.LocalSocketAddress("dev.navix.agent.codex.v1"));
            int termuxUid = android.system.Os.stat("/data/data/com.termux").st_uid;
            if (bridge.getPeerCredentials().getUid() != termuxUid) throw new SecurityException("Invalid Codex bridge owner");
            bridge.setSoTimeout(30000); client.setSoTimeout(0);
            Wire.write(bridge.getOutputStream(), request);
            Thread upstream = new Thread(() -> {
                try { pump(client.getInputStream(), bridge.getOutputStream()); }
                catch (Exception ignored) {}
                finally { try { bridge.close(); client.close(); } catch (Exception ignored) {} }
            }, "navi-codex-upstream");
            upstream.setDaemon(true); upstream.start();
            pump(bridge.getInputStream(), client.getOutputStream());
        } catch (Exception e) {
            try { Wire.write(client.getOutputStream(), new JSONObject().put("event","error").put("message","Codex Termux 中转不可用："+e.getMessage())); }
            catch (Exception ignored) {}
        } finally { try { client.close(); } catch (Exception ignored) {} }
    }
    private static void pump(java.io.InputStream in, java.io.OutputStream out) throws java.io.IOException {
        byte[] bytes = new byte[8192]; int size;
        while ((size=in.read(bytes))!=-1) { out.write(bytes,0,size); out.flush(); }
    }
    private void connect() throws Exception {
        if (ui != null) return;
        UiAutomation candidate = new UiAutomation(thread.getLooper(), new UiAutomationConnection());
        try {
            candidate.connect(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info = candidate.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            candidate.setServiceInfo(info);
            ui = candidate;
        } catch (Exception e) { try { candidate.disconnect(); } catch (Exception ignored) {} throw e; }
    }
    private void clear() { for (AccessibilityNodeInfo n : nodes.values()) n.recycle(); nodes.clear(); signatures.clear(); }
    private static String value(CharSequence s) { String v = s == null ? "" : s.toString(); return v.substring(0, Math.min(240, v.length())); }
    private static String signature(AccessibilityNodeInfo n) {
        Rect r = new Rect(); n.getBoundsInScreen(r);
        return n.getWindowId() + "|" + n.getViewIdResourceName() + "|" + value(n.getText()) + "|" + value(n.getContentDescription()) + "|" + r;
    }
    private void walk(AccessibilityNodeInfo n, String parent, JSONArray list, int depth) throws Exception {
        if (n == null) return;
        if (++visited > 600 || list.length() >= 180 || depth > 40) { n.recycle(); return; }
        String id = generation + ":" + visited;
        boolean visible = n.isVisibleToUser();
        if (visible) {
            Rect r = new Rect(); n.getBoundsInScreen(r);
            JSONArray actions = new JSONArray();
            for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) actions.put(a.getId());
            list.put(new JSONObject().put("node", id).put("parent", parent).put("resourceId", value(n.getViewIdResourceName()))
                .put("text", n.isPassword() ? "[password]" : value(n.getText()))
                .put("description", n.isPassword() ? "" : value(n.getContentDescription()))
                .put("class", value(n.getClassName())).put("bounds", new JSONArray(new int[]{r.left,r.top,r.right,r.bottom}))
                .put("clickable", n.isClickable()).put("longClickable", n.isLongClickable()).put("editable", n.isEditable())
                .put("scrollable", n.isScrollable()).put("enabled", n.isEnabled()).put("actions", actions));
            nodes.put(id, n); signatures.put(id, signature(n));
        }
        for (int i = 0; i < n.getChildCount() && visited < 600 && list.length() < 180; i++) walk(n.getChild(i), visible ? id : parent, list, depth + 1);
        if (!visible) n.recycle();
    }
    private AccessibilityNodeInfo targetRoot() {
        if(requestedDisplay==0&&background.id()<0)return ui.getRootInActiveWindow();
        java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows=ui.getWindowsOnAllDisplays().get(requestedDisplay);
        if(windows==null)return null;
        AccessibilityNodeInfo best=null; int bestScore=-1;
        for(android.view.accessibility.AccessibilityWindowInfo window:windows) {
            try {
                if(window.getType()!=android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)continue;
                AccessibilityNodeInfo root=window.getRoot(); if(root==null)continue;
                int score=window.isFocused()?3:window.isActive()?2:1;
                if(score>bestScore) { if(best!=null)best.recycle(); best=root; bestScore=score; }
                else root.recycle();
            } finally { window.recycle(); }
        }
        return best;
    }
    private JSONObject observe() throws Exception {
        clear(); generation++; visited = 0; ui.clearCache();
        AccessibilityNodeInfo root = targetRoot();
        if (root == null) throw new IllegalStateException("No accessible active window; unlock phone or wait");
        activePackage = value(root.getPackageName()); activeWindow = root.getWindowId(); capturedDisplay=requestedDisplay;
        // Never expose the configuration page, including credentials, to a model.
        if (activePackage.equals("dev.navix.agent") && !containsFixture(root)) { root.recycle(); throw new IllegalStateException("助手页面已隐藏；请先返回目标应用"); }
        JSONArray list = new JSONArray(); walk(root, "", list, 0); captured = SystemClock.elapsedRealtime();
        return new JSONObject().put("ok", true).put("package", activePackage).put("display_id",requestedDisplay).put("generation", generation)
            .put("nodes", list).put("truncated", visited >= 600 || list.length() >= 180);
    }
    private boolean containsFixture(AccessibilityNodeInfo root) {
        java.util.List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId("dev.navix.agent:id/test_status");
        boolean result = !found.isEmpty(); for (AccessibilityNodeInfo n : found) n.recycle(); return result;
    }
    private void restoreTask(int taskId) throws Exception {
        android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic();
        options.setLaunchDisplayId(0); options.setLaunchWindowingMode(1); options.setLaunchBounds(null);
        int result=android.app.ActivityTaskManager.getService().startActivityFromRecents(taskId,options.toBundle());
        if(result<0)throw new IllegalStateException("移回主屏失败："+result);
        for(android.app.ActivityManager.RunningTaskInfo task:android.app.ActivityTaskManager.getService().getTasks(100,false,false,-1))
            if(task.taskId==taskId&&task.displayId==0)return;
        throw new IllegalStateException("系统未将应用移回主屏，保留虚拟屏以便重试");
    }
    private synchronized JSONObject execute(JSONObject q) throws Exception {
        lastRequest = SystemClock.elapsedRealtime();
        String op = q.getString("op");
        if(op.equals("background_open")) { background.open(new Handler(thread.getLooper())); return background.metrics(); }
        if(op.equals("background_restore")) {
            int taskId=q.getInt("task_id"); String component=q.getString("component");
            for(android.app.ActivityManager.RunningTaskInfo task:android.app.ActivityTaskManager.getService().getTasks(100,false,false,-1)) {
                if(task.taskId==taskId&&task.userId==0&&task.baseActivity!=null&&component.equals(task.baseActivity.flattenToShortString())) {
                    if(task.displayId!=0&&(background.id()<1||task.displayId!=background.id()))throw new IllegalStateException("应用已不在此虚拟屏");
                    clear(); restoreTask(taskId);
                    return new JSONObject().put("ok",true).put("task_id",taskId);
                }
            }
            throw new IllegalStateException("后台任务已结束，请刷新列表");
        }
        if(op.equals("background_close")) {
            clear();
            if(background.id()>0)for(android.app.ActivityManager.RunningTaskInfo task:android.app.ActivityTaskManager.getService().getTasks(100,false,false,-1))
                if(task.displayId==background.id()&&task.userId==0)restoreTask(task.taskId);
            background.close(); return new JSONObject().put("ok",true);
        }
        if(op.equals("background_status")) {
            JSONArray tasks=new JSONArray();
            if(background.id()>0)for(android.app.ActivityManager.RunningTaskInfo task:android.app.ActivityTaskManager.getService().getTasks(100,false,false,-1))
                if(task.displayId==background.id()&&task.userId==0&&task.baseActivity!=null)tasks.put(new JSONObject().put("task_id",task.taskId)
                    .put("component",task.baseActivity.flattenToShortString()).put("package",task.baseActivity.getPackageName()));
            return background.metrics().put("tasks",tasks);
        }
        requestedDisplay=q.optInt("display_id",0);
        if(requestedDisplay!=0 && (requestedDisplay<0||requestedDisplay!=background.id()))throw new IllegalArgumentException("后台虚拟屏已失效；不会回退操作主屏");
        if (op.equals("ping")) return new JSONObject().put("ok", true).put("uid", android.os.Process.myUid()).put("connected", ui != null);
        if (op.equals("release")) { clear(); if (ui != null) { try { ui.disconnect(); } finally { ui = null; } } return new JSONObject().put("ok", true); }
        if (op.equals("launch") || op.equals("launch_app") || op.equals("start_intent") || op.equals("am")) {
            List<String> args = AmCommand.arguments(q);
            if(requestedDisplay!=0) {
                if(op.equals("am")) {
                    if(!Arrays.asList("help","get-current-user","get-config","to-uri","to-intent-uri","to-app-uri").contains(args.get(0)))
                        throw new IllegalArgumentException("后台模式禁止通用 am 修改操作，请使用 launch_app/start_intent/switch_app，避免影响主屏");
                } else { args.add("--display"); args.add(Integer.toString(requestedDisplay)); }
            }
            // Match /system/bin/am dispatch without leaving a shell parent on timeout.
            List<String> argv = new ArrayList<>();
            if (args.get(0).equals("instrument")) argv.add("/system/bin/am");
            else { argv.add("/system/bin/cmd"); argv.add("activity"); }
            argv.addAll(args);
            clear();
            return CommandRunner.run(argv, 8000, 16000).put("requiresObservation", true);
        }
        if (op.equals("switch_app")) {
            String pkg = q.getString("package");
            if (!pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("Invalid package");
            clear();
            for (android.app.ActivityManager.RunningTaskInfo task : android.app.ActivityTaskManager.getService().getTasks(100, false, false, -1)) {
                if (task.userId != 0) continue;
                android.content.ComponentName base = task.baseActivity;
                if (base != null && pkg.equals(base.getPackageName())) {
                    if(task.displayId!=requestedDisplay) {
                        android.app.ActivityOptions options=android.app.ActivityOptions.makeBasic(); options.setLaunchDisplayId(requestedDisplay); options.setLaunchWindowingMode(1); options.setLaunchBounds(null);
                        android.app.ActivityTaskManager.getService().startActivityFromRecents(task.taskId,options.toBundle());
                    } else {
                        android.app.ActivityTaskManager.getService().moveTaskToFront(null, "com.android.shell", task.taskId, 0, null);
                    }
                    return new JSONObject().put("ok",true).put("taskId",task.taskId).put("resumed",true).put("requiresObservation",true);
                }
            }
            if (q.optBoolean("launch_if_missing",true)) {
                JSONObject launched = execute(new JSONObject().put("op","launch_app").put("package",pkg).put("display_id",requestedDisplay));
                return launched.put("resumed",false).put("reason","No existing task; launcher start requested");
            }
            return new JSONObject().put("ok",false).put("error","No existing task; application was not launched");
        }
        if (op.equals("input_text") || op.equals("input_key")) {
            if(requestedDisplay!=0)throw new IllegalStateException("后台模式不切换主屏输入法；使用 phone set_text 操作支持文字编辑的节点。终端等仅输入法编辑器暂不支持。");
            connect(); ui.clearCache();
            AccessibilityNodeInfo root = targetRoot();
            boolean matches = root != null && q.getString("package").equals(value(root.getPackageName()));
            if (root != null) root.recycle();
            if (!matches) throw new IllegalStateException("Target application is not in foreground; observe first");
            clear(); return RootInput.send(q, appUid);
        }
        connect();
        if (op.equals("observe")) return observe();
        boolean ok;
        if (op.equals("home") || op.equals("back")) {
            clear();
            if(requestedDisplay!=0) {
                if(op.equals("home"))throw new IllegalArgumentException("后台虚拟屏没有桌面，请用 switch_app 切换应用");
                long now=SystemClock.uptimeMillis();
                android.view.KeyEvent down=new android.view.KeyEvent(now,now,android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_BACK,0);
                android.view.KeyEvent up=new android.view.KeyEvent(now,now,android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_BACK,0);
                down.setDisplayId(requestedDisplay); up.setDisplayId(requestedDisplay);
                boolean sent=ui.injectInputEvent(down,true); ok=ui.injectInputEvent(up,true)&&sent;
            } else ok = ui.performGlobalAction(op.equals("home") ? 2 : 1);
        }
        else {
            int action;
            switch (op) {
                case "click": action = AccessibilityNodeInfo.ACTION_CLICK; break;
                case "long_click": action = AccessibilityNodeInfo.ACTION_LONG_CLICK; break;
                case "set_text": action = AccessibilityNodeInfo.ACTION_SET_TEXT; break;
                case "scroll_forward": action = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD; break;
                case "scroll_backward": action = AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD; break;
                default: throw new IllegalArgumentException("Unknown operation");
            }
            String id = q.getString("node"); AccessibilityNodeInfo n = nodes.get(id);
            if (capturedDisplay!=requestedDisplay || n == null || SystemClock.elapsedRealtime() - captured > 30000) throw new IllegalStateException("Stale node; observe again");
            ui.clearCache();
            AccessibilityNodeInfo root = targetRoot();
            boolean sameWindow = root != null && root.getWindowId() == activeWindow && activePackage.equals(value(root.getPackageName()));
            if (root != null) root.recycle();
            if (!sameWindow || !n.refresh() || !n.isVisibleToUser() || !n.isEnabled() || !signature(n).equals(signatures.get(id))) throw new IllegalStateException("Page changed; observe again");
            if (n.isPassword()) throw new IllegalStateException("Password fields require manual input");
            boolean supported = false;
            for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) if (a.getId() == action) supported = true;
            if (!supported) throw new IllegalArgumentException("Node does not support action");
            Bundle args = new Bundle();
            if (op.equals("set_text")) {
                String text = q.getString("text"); if (text.length() > 2000) throw new IllegalArgumentException("Text too long");
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            }
            ok = n.performAction(action, args); clear();
        }
        return new JSONObject().put("ok", ok).put("requiresObservation", true);
    }
}
