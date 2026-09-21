package com.gvchat.common.payment.channel.infra.crypto;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 支付渠道签名/加解密工具：MD5/HMAC-SHA256（微信 v2、Stripe）、RSA2/SHA256withRSA（支付宝、微信 v3）、AES-256-GCM（微信 v3）。 */
public final class PaymentCryptoUtils {

    private PaymentCryptoUtils() {
    }

    public static String md5Hex(String data) {
        try {
            return hex(MessageDigest.getInstance("MD5").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("MD5 计算失败", e);
        }
    }

    public static String hmacSha256Hex(String data, String key) {
        return hex(hmacSha256(data.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8)));
    }

    public static byte[] hmacSha256(byte[] data, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    public static boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    /** RSA2（SHA256withRSA）签名，私钥为 PKCS#8 PEM（BEGIN PRIVATE KEY），支付宝与微信 v3 通用。 */
    public static String rsa2Sign(String content, String privateKeyPem) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(parsePrivateKey(privateKeyPem));
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("RSA2 签名失败", e);
        }
    }

    /** RSA2（SHA256withRSA）验签，公钥为 X.509 PEM（BEGIN PUBLIC KEY），支付宝与微信 v3 通用；签名不合法返回 false。 */
    public static boolean rsa2Verify(String content, String sign, String publicKeyPem) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(parsePublicKey(publicKeyPem));
            signature.update(content.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(sign));
        } catch (Exception e) {
            return false;
        }
    }

    /** 微信 v3 回调 resource 解密：AEAD_AES_256_GCM，key 为 APIv3 密钥（32 字节 UTF-8），nonce 12 字节。 */
    public static String aes256GcmDecrypt(String base64Ciphertext, String key, String nonce, String associatedData) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, nonce.getBytes(StandardCharsets.UTF_8)));
            if (associatedData != null && !associatedData.isEmpty()) {
                cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            }
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(base64Ciphertext));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("微信 v3 AES-GCM 解密失败", e);
        }
    }

    private static PrivateKey parsePrivateKey(String pem) {
        try {
            byte[] decoded = Base64.getDecoder().decode(stripPem(pem));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException("解析 RSA 私钥失败（需 PKCS#8 PEM）", e);
        }
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            byte[] decoded = Base64.getDecoder().decode(stripPem(pem));
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
        } catch (Exception e) {
            throw new IllegalStateException("解析 RSA 公钥失败", e);
        }
    }

    private static String stripPem(String pem) {
        if (pem == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        pem.lines().forEach(line -> {
            String trimmed = line.trim();
            if (!trimmed.startsWith("-----")) {
                sb.append(trimmed);
            }
        });
        return sb.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
