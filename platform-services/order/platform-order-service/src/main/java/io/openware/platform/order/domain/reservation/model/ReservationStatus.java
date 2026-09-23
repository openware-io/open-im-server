package io.openware.platform.order.domain.reservation.model;

/**
 * 预约状态机（SAAS_PLATFORM_04 §6.3）。
 * PENDING → CONFIRMED → ARRIVED → CONVERTED；
 * 异常分支：CANCELLED（取消）、NO_SHOW（未到店）。
 */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    ARRIVED,
    CANCELLED,
    NO_SHOW,
    CONVERTED
}