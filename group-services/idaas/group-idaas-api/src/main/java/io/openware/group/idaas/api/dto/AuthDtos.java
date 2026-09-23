package io.openware.group.idaas.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 认证域 DTO：集团管理员登录（Redis 会话 + 一次性 SSO 票据）。
 */
public final class AuthDtos {
  private AuthDtos() {}

  /**
   * 登录请求。
   *
   * <p>{@code ttlHours} = 登录有效时长（小时），门户「登录有效时长」下拉用。
   * 只接受白名单 {@code 2 / 24 / 168 / 720}（见 {@code GroupSessionTtl}），非法值回退默认 24 小时，
   * 避免客户端指定超长有效期。
   */
  public record LoginRequest(
      @NotBlank(message = "用户名不能为空") String username,
      @NotBlank(message = "密码不能为空") String password,
      Integer ttlHours) {
    public LoginRequest(String username, String password) {
      this(username, password, null);
    }
  }

  /** 登录响应：服务端会话 ID（Redis）+ 过期时间（epoch 毫秒）。 */
  public record LoginResponse(String sessionId, long expiresAt) {}

  /**
   * 当前会话查询响应。
   *
   * <p>{@code expiresAt} 为会话到期时间（epoch 毫秒，0 = 未知）：门户据此展示「本次登录有效期至 …」，
   * 并让用户知道从集团入口跳到各后台时**继承的就是这个有效期**。
   */
  public record SessionResponse(long accountId, String username, boolean authenticated, long expiresAt) {
    /** 兼容旧调用：不带到期时间。 */
    public SessionResponse(long accountId, String username, boolean authenticated) {
      this(accountId, username, authenticated, 0L);
    }
  }

  /** 一次性 SSO 票据响应。 */
  public record SsoTicketResponse(String ticket, long expiresAt) {}

  /** SSO 票据校验请求。 */
  public record SsoVerifyRequest(
      @NotBlank(message = "票据不能为空") String ticket) {}

  /** SSO 票据校验响应。 */
  public record SsoVerifyResponse(long accountId, String username, boolean valid, long expiresAt) {}
}
