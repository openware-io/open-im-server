package io.openware.platform.identity.api.dto;

/**
 * 账号响应（API 契约）。
 *
 * <p>此前 `AccountController` 与 `InternalAccountController` 直接返回持久化对象 `IdentityAccountPo`，
 * 使 HTTP 契约与表映射耦合：改表、加 MyBatis 注解或调整映射都会直接改变对外响应。本类型把对外形状固定。
 *
 * <p>本类型只描述对外形状，不含任何转换逻辑——领域模型到本类型的映射由 `api.converter.AccountConverter`
 * 承担（standards/10：`api.converter` 负责 DTO 与用例输出互转，契约类型本身不触碰领域对象）。
 *
 * <p>相对原 PO 响应，本视图**不暴露** createdBy/createdAt/updatedBy/updatedAt 四个记账字段：它们是
 * 持久化记账信息，不是账号的业务事实。这是有意为之的契约收敛。
 */
public record AccountView(Long id, String status, String accountType) {
}
