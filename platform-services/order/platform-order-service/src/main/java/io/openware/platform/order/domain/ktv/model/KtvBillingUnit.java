package io.openware.platform.order.domain.ktv.model;

/**
 * 包厢计费单位（KTV_BUSINESS_01 §2.1，SAAS_PLATFORM_04 §6.3）。
 */
public enum KtvBillingUnit {
    HOUR,
    HALF_HOUR,
    PACKAGE;

    /** 解析持久化字符串，未知或空回退默认 {@link #HOUR}。 */
    public static KtvBillingUnit fromCode(String code) {
        if (code == null || code.isBlank()) {
            return HOUR;
        }
        for (KtvBillingUnit v : values()) {
            if (v.name().equals(code)) {
                return v;
            }
        }
        return HOUR;
    }

    /** 计费单位秒数；PACKAGE 为套餐特判，返回 0 表示不可按时长换算。 */
    public int seconds() {
        return switch (this) {
            case HOUR -> 3600;
            case HALF_HOUR -> 1800;
            case PACKAGE -> 0;
        };
    }
}
