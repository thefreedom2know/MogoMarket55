package com.mogomarket.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the e-mail login used by the fingerprint button. The password is encrypted with an
 * AES key that lives in the Android Keystore (it never leaves the secure hardware), instead of
 * being kept as plain text. Legacy plain-text entries are migrated on first read.
 */
final class SecureCredentials {

    private static final String TAG = "SecureCredentials";
    private static final String ALIAS = "mogo_login_key";
    private static final String PREFS = "auth_prefs_secure";
    private static final String LEGACY_PREFS = "auth_prefs";

    private SecureCredentials() { }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }

    private static String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] data = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                + Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        String[] parts = stored.split(":");
        if (parts.length != 2) throw new IllegalArgumentException("bad format");
        byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
        byte[] data = Base64.decode(parts[1], Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
    }

    static void save(Context context, String email, String password) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("email", email)
                    .putString("password", encrypt(password))
                    .apply();
            context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().clear()
                    .apply();
        } catch (Exception e) {
            Log.e(TAG, "Could not save the login securely", e);
        }
    }

    /** Returns {email, password}, or null when nothing usable is saved. */
    @Nullable
    static String[] load(Context context) {
        try {
            SharedPreferences secure = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String email = secure.getString("email", null);
            String stored = secure.getString("password", null);
            if (email != null && stored != null) {
                return new String[]{email, decrypt(stored)};
            }
            // migrate a plain-text login saved by an older version
            SharedPreferences legacy = context.getSharedPreferences(LEGACY_PREFS,
                    Context.MODE_PRIVATE);
            String oldEmail = legacy.getString("email", null);
            String oldPass = legacy.getString("password", null);
            if (oldEmail != null && oldPass != null) {
                save(context, oldEmail, oldPass);
                return new String[]{oldEmail, oldPass};
            }
        } catch (Exception e) {
            // key lost (new phone, restored backup, reset lock screen...): ask for a fresh login
            Log.w(TAG, "Saved login can't be decrypted: " + e.getMessage());
            clear(context);
        }
        return null;
    }

    static boolean has(Context context) {
        return load(context) != null;
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
