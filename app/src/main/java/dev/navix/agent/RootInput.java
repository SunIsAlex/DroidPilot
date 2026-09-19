package dev.navix.agent;

import android.net.*;
import android.os.SystemClock;
import org.json.*;
import java.util.*;

final class RootInput {
    private static JSONObject command(String... args) throws Exception {
        return CommandRunner.run(Arrays.asList(args), 2000, 8000);
    }
    private static void ime(String operation, String id) throws Exception {
        JSONObject result = command("/system/bin/cmd", "input_method", operation, "--user", "0", id);
        if (!result.optBoolean("ok")) throw new IllegalStateException(result.optString("output", result.toString()));
    }
    static JSONObject send(JSONObject q, int appUid) throws Exception {
        JSONObject setting = command("/system/bin/settings","--user","0","get","secure","default_input_method");
        String previous = setting.optString("output").trim();
        if (!setting.optBoolean("ok") || !previous.contains("/")) throw new IllegalStateException("Cannot determine keyboard to restore");
        JSONObject enabled = command("/system/bin/settings","--user","0","get","secure","enabled_input_methods");
        if (!enabled.optBoolean("ok")) throw new IllegalStateException("Cannot read enabled keyboards");
        boolean wasEnabled = false;
        for (String item : enabled.optString("output").trim().split(":"))
            if (item.split(";")[0].equals(AgentIme.ID)) wasEnabled = true;
        JSONObject result = null;
        try {
            ime("enable", AgentIme.ID); ime("set", AgentIme.ID);
            LocalSocket connected = null;
            long until = SystemClock.elapsedRealtime() + 3000;
            while (connected == null && SystemClock.elapsedRealtime() < until) {
                LocalSocket candidate = new LocalSocket();
                try { candidate.connect(new LocalSocketAddress(AgentIme.SOCKET)); connected = candidate; }
                catch (java.io.IOException e) { candidate.close(); SystemClock.sleep(100); }
            }
            if (connected == null) throw new IllegalStateException("Input keyboard unavailable");
            try (LocalSocket socket = connected) {
                if (socket.getPeerCredentials().getUid() != appUid) throw new SecurityException("Invalid keyboard owner");
                socket.setSoTimeout(3000);
                // Wait for Android to reconnect the focused editor after the IME switch.
                SystemClock.sleep(300);
                Wire.write(socket.getOutputStream(), q);
                result = Wire.read(socket.getInputStream());
            }
        } catch (Exception e) {
            result = new JSONObject().put("ok",false).put("error",e.getClass().getSimpleName()+": "+e.getMessage()+"; observe before retrying input");
        } finally {
            try {
                if (!previous.equals(AgentIme.ID)) ime("set", previous);
                if (!wasEnabled && !previous.equals(AgentIme.ID)) ime("disable", AgentIme.ID);
            } catch (Exception e) {
                if (result == null) result = new JSONObject().put("ok",false);
                result.put("keyboardRestoreError",e.getMessage());
            }
        }
        return result.put("requiresObservation",true);
    }
}
