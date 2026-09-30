package io.openware.platform.admin.api.ktv;

/** Order 域预约窗口的 BFF DTO；时间单位统一为分钟。 */
public record ReservationRuleConfig(Long id, Long storeId, String businessType, Integer advanceMinutes,
                                    Integer cancelMinutes, Integer rescheduleMinutes, Integer version,
                                    String idempotencyKey) {}
