package com.dutch.iam.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class IdentifierProtector {
    private static final int NONCE_BYTES = 12;
    private final byte[] lookupKey;
    private final byte[] encryptionKey;
    private final int keyVersion;
    private final SecureRandom random = new SecureRandom();

    public IdentifierProtector(byte[] lookupKey, byte[] encryptionKey, int keyVersion) {
        this.lookupKey = Objects.requireNonNull(lookupKey).clone();
        this.encryptionKey = Objects.requireNonNull(encryptionKey).clone();
        if (this.lookupKey.length < 32 || this.encryptionKey.length != 32 || keyVersion < 1) {
            throw new IllegalArgumentException("Identifier protection keys or version are invalid.");
        }
        this.keyVersion = keyVersion;
    }

    public byte[] lookupDigest(IdentifierType type, String rawValue) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(lookupKey, "HmacSHA256"));
            return mac.doFinal((type.name() + ":" + canonical(rawValue)).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Identifier lookup protection unavailable.", exception);
        }
    }

    public byte[] encrypt(IdentifierType type, String rawValue) {
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(type.name().getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(canonical(rawValue).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Identifier encryption unavailable.", exception);
        }
    }

    public int keyVersion() { return keyVersion; }

    private static String canonical(String value) {
        if (value == null || value.isBlank() || value.length() > 320) {
            throw new IllegalArgumentException("Identifier is invalid.");
        }
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
