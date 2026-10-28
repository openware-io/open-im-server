package io.openware.platform.tenant.domain.iam.model;

/** 角色绑定安全描述：用于按 bindingId 撤销时校验租户/平台归属。 */
public record BindingDescriptor(
    Long id,
    String subjectType,
    Long subjectId,
    Long tenantId,
    String scopeType,
    int authorizationVersion) {
}
