package dev.navix.agent;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Length-prefixed JSON over a local Unix socket, never a network listener. */
public final class Wire {
    static final String SOCKET = "dev.navix.agent.root.v1";
    static JSONObject read(InputStream stream) throws Exception {
        DataInputStream in = new DataInputStream(stream);
        int size = in.readInt();
        if (size < 2 || size > 262144) throw new IOException("Invalid frame size");
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }
    static void write(OutputStream stream, JSONObject object) throws Exception {
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 262144) throw new IOException("Frame too large");
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(bytes.length); out.write(bytes); out.flush();
    }
    static JSONObject call(JSONObject request) throws Exception {
        try (LocalSocket socket = new LocalSocket()) {
            socket.connect(new LocalSocketAddress(SOCKET));
            socket.setSoTimeout(request.optString("op").startsWith("input_") ? 30000 : 15000);
            if (socket.getPeerCredentials().getUid() != 0) throw new SecurityException("Root peer required");
            write(socket.getOutputStream(), request);
            return read(socket.getInputStream());
        }
    }
    public static void main(String[] args) throws Exception {
        try { System.out.println(call(new JSONObject(args[0]))); }
        catch (Exception e) { System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage()); System.exit(1); }
    }
}
