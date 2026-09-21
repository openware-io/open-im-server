package com.gvchat.common.mail.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 收件人隐私工具：邮箱摘要落库 + 脱敏展示，杜绝明文落库。 */
public final class RecipientPrivacy {

    private RecipientPrivacy() {
    }

    /** SHA-256 十六进制摘要；仅摘要落库。 */
    public static String digest(String email) {
        if (email == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(email.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 脱敏：保留邮箱首字符，@ 前本地部分以 *** 代替，如 a***@example.com。 */
    public static String mask(String email) {
        if (email == null || email.isEmpty()) {
            return email;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return email;
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
