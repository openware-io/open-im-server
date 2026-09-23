package io.openware.common.sms.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 手机号隐私工具：摘要落库 + 脱敏展示，杜绝明文落库。 */
public final class PhonePrivacy {

    private PhonePrivacy() {
    }

    /** SHA-256 十六进制摘要；仅摘要落库。 */
    public static String digest(String phone) {
        if (phone == null) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(phone.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 脱敏：保留前 3 后 4，中间以 **** 代替；如 138****1234。 */
    public static String mask(String phone) {
        if (phone == null || phone.isEmpty()) {
            return phone;
        }
        if (phone.length() <= 7) {
            return phone.charAt(0) + "****";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
