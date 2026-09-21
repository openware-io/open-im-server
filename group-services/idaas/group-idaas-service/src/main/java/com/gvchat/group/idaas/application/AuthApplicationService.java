package com.gvchat.group.idaas.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.group.idaas.api.dto.AuthDtos.LoginRequest;
import com.gvchat.group.idaas.api.dto.AuthDtos.LoginResponse;
import com.gvchat.group.idaas.api.dto.AuthDtos.SessionResponse;
import com.gvchat.group.idaas.api.dto.AuthDtos.SsoTicketResponse;
import com.gvchat.group.idaas.api.dto.AuthDtos.SsoVerifyResponse;
import com.gvchat.group.idaas.infra.persistence.mapper.GroupAccountMapper;
import com.gvchat.group.idaas.infra.persistence.po.GroupAccountPo;
import com.gvchat.group.idaas.infra.security.IdaasSessionStore;
import com.gvchat.group.idaas.infra.security.GroupSessionTtl;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证域：集团管理员登录，登录态存 Redis（服务端权威），浏览器仅持有 HttpOnly Cookie 会话 ID。
 */
@Service
@Slf4j
public class AuthApplicationService {

  private static final String ACCOUNT_ENABLED = "ENABLED";
  private static final Duration TICKET_TTL = Duration.ofSeconds(60);

  private final GroupAccountMapper accountMapper;
  private final PasswordEncoder passwordEncoder;
  private final IdaasSessionStore sessionStore;

  public AuthApplicationService(GroupAccountMapper accountMapper, PasswordEncoder passwordEncoder,
      IdaasSessionStore sessionStore) {
    this.accountMapper = accountMapper;
    this.passwordEncoder = passwordEncoder;
    this.sessionStore = sessionStore;
  }

  /** 登录：校验 BCrypt 后创建 Redis 会话，返回 sessionId。 */
  public LoginResponse login(LoginRequest request) {
    GroupAccountPo account = accountMapper.selectOne(
        new QueryWrapper<GroupAccountPo>().eq("username", request.username()));
    if (account == null || !ACCOUNT_ENABLED.equals(account.getStatus())
        || !passwordEncoder.matches(request.password(), account.getPassword())) {
      log.warn("IDaaS login rejected, username={}", maskUsername(request.username()));
      throw new ApiException(401, "AUTH_INVALID_CREDENTIAL", "用户名或密码错误");
    }
    Duration ttl = GroupSessionTtl.resolve(request.ttlHours());
    String sessionId = ttl.equals(IdaasSessionStore.DEFAULT_SESSION_TTL)
        ? sessionStore.createSession(account.getId(), account.getUsername())
        : sessionStore.createSession(account.getId(), account.getUsername(), ttl);
    log.info("IDaaS login success, accountId={}, username={}", account.getId(), maskUsername(account.getUsername()));
    long expiresAt = sessionStore.sessionExpiresAt(sessionId);
    if (expiresAt <= 0) expiresAt = System.currentTimeMillis() + ttl.toMillis();
    return new LoginResponse(sessionId, expiresAt);
  }

  /** 查询当前会话（门户「已登录」态判断 + 展示本次登录有效期至）。 */
  public SessionResponse currentSession(String sessionId) {
    return sessionStore.findSession(sessionId)
        .map(user -> {
          long expiresAt = user.expiresAt() > 0 ? user.expiresAt() : sessionStore.sessionExpiresAt(sessionId);
          return new SessionResponse(user.accountId(), user.username(), true, Math.max(0L, expiresAt));
        })
        .orElse(new SessionResponse(0, "", false, 0L));
  }

  /** 基于会话签发一次性 SSO 票据。 */
  public SsoTicketResponse issueTicket(String sessionId) {
    String ticket = sessionStore.issueTicket(sessionId);
    long sessionExpiresAt = sessionStore.sessionExpiresAt(sessionId);
    long ticketExpiresAt = System.currentTimeMillis() + TICKET_TTL.toMillis();
    if (sessionExpiresAt > 0) ticketExpiresAt = Math.min(sessionExpiresAt, ticketExpiresAt);
    return new SsoTicketResponse(ticket, ticketExpiresAt);
  }

  /** 消费一次性 SSO 票据（用后即焚），返回账号信息。 */
  public SsoVerifyResponse verifySso(String ticket) {
    IdaasSessionStore.IdaasUser user = sessionStore.consumeTicket(ticket)
        .orElseThrow(() -> new ApiException(401, "SSO_TICKET_INVALID", "SSO 票据无效或已过期"));
    if (user.expiresAt() > 0 && user.expiresAt() <= System.currentTimeMillis()) {
      throw new ApiException(401, "SSO_TICKET_EXPIRED", "集团会话已过期");
    }
    return new SsoVerifyResponse(user.accountId(), user.username(), true, user.expiresAt());
  }

  /** 退出登录：删除 Redis 会话。 */
  public void logout(String sessionId) {
    sessionStore.deleteSession(sessionId);
  }

  /** 用户名脱敏：日志不落明文账号，避免账号枚举与 PII 泄露。 */
  private static String maskUsername(String username) {
    if (username == null || username.isEmpty()) {
      return username;
    }
    return username.length() <= 2 ? username.charAt(0) + "***" : username.charAt(0) + "***" + username.charAt(username.length() - 1);
  }
}
