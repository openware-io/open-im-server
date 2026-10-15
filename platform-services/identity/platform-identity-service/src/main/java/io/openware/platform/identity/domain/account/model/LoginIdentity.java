package io.openware.platform.identity.domain.account.model;

/**
 * 登录标识（领域模型）：账号下的一条可登录凭据，例如手机号／邮箱／用户名。
 *
 * <p>审计字段由适配器填充，不在此承载（见 standards/10“聚合装配与持久化契约”）。
 *
 * <p>{@code credential} 是登录凭据（摘要／哈希后的形态由上层决定），属敏感字段：不得进入日志、
 * 不得作为 API 响应直接返回。
 *
 * @param id              登录标识主键
 * @param accountId       所属账号
 * @param loginType       登录类型：PHONE / EMAIL / USERNAME 等
 * @param loginIdentifier 登录标识（手机号／邮箱／用户名本身）
 * @param credential      登录凭据
 * @param status          该登录标识的状态
 */
public record LoginIdentity(
    Long id,
    Long accountId,
    String loginType,
    String loginIdentifier,
    String credential,
    String status) {
}
