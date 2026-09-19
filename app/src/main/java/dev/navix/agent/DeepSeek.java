package dev.navix.agent;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
final class DeepSeek {
    static final class HttpError extends IOException {
        final boolean retryable;
        HttpError(int status) { super("DeepSeek HTTP "+status+"；请检查密钥、余额和模型名称"); retryable=status==429||status>=500; }
    }
    volatile HttpURLConnection active;
    volatile boolean cancelled;
    void cancel() { cancelled = true; HttpURLConnection c = active; if (c != null) c.disconnect(); }
    JSONObject request(String key, String path, JSONObject body) throws Exception {
        if (cancelled) throw new InterruptedException();
        HttpURLConnection c = (HttpURLConnection)new URL("https://api.deepseek.com" + path).openConnection();
        active = c;
        try {
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(15000); c.setReadTimeout(45000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            if (body != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            if (cancelled) throw new InterruptedException();
            int status = c.getResponseCode();
            if (status != 200) throw new HttpError(status);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (cancelled) throw new InterruptedException();
                    bytes.write(buffer,0,count); if (bytes.size() > 262144) throw new IOException("Response too large");
                }
            }
            return new JSONObject(bytes.toString("UTF-8"));
        } finally { c.disconnect(); active = null; }
    }
}
