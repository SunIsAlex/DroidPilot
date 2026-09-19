package dev.navix.agent;

import android.inputmethodservice.InputMethodService;
import android.net.*;
import android.os.*;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.inputmethod.*;
import org.json.*;
import java.util.concurrent.*;

/** Temporary keyboard. Only the root daemon may submit an input request. */
public final class AgentIme extends InputMethodService {
    static final String SOCKET = "dev.navix.agent.ime.v1";
    static final String ID = "dev.navix.agent/.AgentIme";
    private LocalServerSocket server;
    private final Handler main = new Handler(Looper.getMainLooper());
    @Override public void onCreate() {
        super.onCreate();
        new Thread(() -> {
            try (LocalServerSocket listener = new LocalServerSocket(SOCKET)) {
                synchronized (this) { if (destroyed) return; server = listener; }
                while (!destroyed) {
                    try (LocalSocket client = listener.accept()) {
                        client.setSoTimeout(5000);
                        if (client.getPeerCredentials().getUid() != 0) continue;
                        JSONObject request = Wire.read(client.getInputStream());
                        FutureTask<JSONObject> work = new FutureTask<>(() -> input(request));
                        main.post(work);
                        JSONObject result;
                        try { result = work.get(2, TimeUnit.SECONDS); }
                        catch (Exception e) { work.cancel(false); result = new JSONObject().put("ok",false).put("error","Input outcome unknown; observe before retrying: " + e.getClass().getSimpleName()); }
                        Wire.write(client.getOutputStream(), result);
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
        }, "navi-ime").start();
    }
    private volatile boolean destroyed;
    private boolean editorActive;
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting); editorActive = true;
    }
    @Override public void onFinishInput() {
        editorActive = false; super.onFinishInput();
    }
    @Override public void onDestroy() {
        destroyed = true;
        synchronized (this) { try { if (server != null) server.close(); } catch (Exception ignored) {} }
        super.onDestroy();
    }
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    private JSONObject input(JSONObject q) throws Exception {
        EditorInfo editor = getCurrentInputEditorInfo();
        InputConnection connection = getCurrentInputConnection();
        if (!editorActive || editor == null || connection == null) return new JSONObject().put("ok",false).put("error","No focused editor; tap the input area first");
        if (!q.getString("package").equals(editor.packageName) || "dev.navix.agent".equals(editor.packageName))
            return new JSONObject().put("ok",false).put("error","Focused editor package changed");
        int cls = editor.inputType & InputType.TYPE_MASK_CLASS, variation = editor.inputType & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            || cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            return new JSONObject().put("ok",false).put("error","Password fields require manual input");
        boolean accepted;
        if (q.getString("op").equals("input_text")) {
            String text = q.getString("text");
            if (text.length() > 2000 || text.indexOf('\0') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
                throw new IllegalArgumentException("Use input_key ENTER separately; text must be a single line, at most 2000 characters");
            accepted = connection.commitText(text, 1);
        } else {
            String key = q.getString("key");
            int code;
            switch (key) {
                case "ENTER": code=KeyEvent.KEYCODE_ENTER; break;
                case "TAB": code=KeyEvent.KEYCODE_TAB; break;
                case "BACKSPACE": code=KeyEvent.KEYCODE_DEL; break;
                case "ESCAPE": code=KeyEvent.KEYCODE_ESCAPE; break;
                default: throw new IllegalArgumentException("Unknown key");
            }
            long time = SystemClock.uptimeMillis();
            boolean down = connection.sendKeyEvent(new KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,0));
            boolean up = connection.sendKeyEvent(new KeyEvent(time,SystemClock.uptimeMillis(),KeyEvent.ACTION_UP,code,0));
            accepted = down && up;
        }
        return new JSONObject().put("ok",accepted).put("package",editor.packageName)
            .put("delivery", "InputConnection accepted request; verify actual content with observe");
    }
}
