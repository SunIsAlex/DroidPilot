package dev.navix.agent;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
final class Secrets {
    private static javax.crypto.SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias("navi-api")) {
            KeyGenerator gen = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder("navi-api", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        return (javax.crypto.SecretKey) store.getKey("navi-api", null);
    }
    static void save(Context ctx, String value) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key());
        ctx.getSharedPreferences("config",0).edit().putString("iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP))
            .putString("secret", Base64.encodeToString(c.doFinal(value.getBytes("UTF-8")), Base64.NO_WRAP)).apply();
    }
    static String load(Context ctx) throws Exception {
        android.content.SharedPreferences p = ctx.getSharedPreferences("config",0);
        if (!p.contains("secret")) return "";
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(p.getString("iv",""),Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(p.getString("secret",""),Base64.NO_WRAP)),"UTF-8");
    }
}
