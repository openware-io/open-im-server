package io.openware.platform.order.domain.ktv.model;

/**
 * 服务人员点单状态机（KTV_BUSINESS_01 §1.4/§5.2，SAAS_PLATFORM_04 §6.3）。
 * ORDERED → SERVING → ENDED；异常 CANCELLED（取消仅限 ORDERED 且未计费）。
 */
public enum KtvServerSessionStatus {
    ORDERED,
    SERVING,
    ENDED,
    CANCELLED
}
