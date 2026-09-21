package com.gvchat.common.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 字段级 AES-256-GCM 加解密（简易实现，满足规范内最小要求）。
 *
 * <p>密钥：由配置 {@code app.crypto.aes-secret} 经 SHA-256 派生 32 字节 AES-256 密钥（避免配置长度敏感）。
 * 密文格式：{@code v1:base64(iv(12B) || ciphertext+tag)}，带版本前缀便于将来密钥轮换/算法升级。
 *
 * <p>简化点与后续升级（详见 docs/renovation/COMMON_FOUNDATION_01_SERVICE.md）：
 * 密钥托管（KMS）、密钥轮换、按字段分级密钥等为后续迭代；本实现仅保证「不落明文」与可逆可读。
 */
@Component
public class AesGcmCipher {
    private static final String VERSION = "v1";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    public AesGcmCipher(@Value("${app.crypto.aes-secret:gv-aes-dev-secret-change-me}") String secret) {
        this.keySpec = new SecretKeySpec(sha256(secret), "AES");
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return VERSION + ":" + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 加密失败", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        try {
            int idx = ciphertext.indexOf(':');
            if (idx <= 0) throw new IllegalArgumentException("密文缺少版本前缀");
            String version = ciphertext.substring(0, idx);
            if (!VERSION.equals(version)) throw new IllegalArgumentException("不支持的密文版本: " + version);
            byte[] data = Base64.getDecoder().decode(ciphertext.substring(idx + 1));
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(data, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 解密失败", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}