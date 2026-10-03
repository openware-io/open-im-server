package io.openware.platform.admin.api.ktv;

/** Order 域作废审批要求的 BFF DTO。 */
public record VoidRuleConfig(Long id, Long storeId, String businessType, Boolean requireApproval,
                             Integer version, String idempotencyKey) { }
