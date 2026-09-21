package com.gvchat.im.user.api;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析工具。
 *
 * <p>请求经 gateway（及可能的反向代理/Ingress）后，{@code request.getRemoteAddr()} 拿到的是
 * 网关内网地址而非真实客户端 IP。统一取 {@code X-Forwarded-For} 首段（最左侧=原始客户端），
 * 缺失时回退 {@code getRemoteAddr()}，用于多端登录设备会话、账号注销审计等场景记录登录来源 IP。
 */
public final class ClientIpUtil {

  private ClientIpUtil() {
  }

  /** 解析真实客户端 IP。 */
  public static String resolve(HttpServletRequest request) {
    String forwardedFor = request.getHeader("X-Forwarded-For");
    if (forwardedFor != null && !forwardedFor.isBlank()) {
      String first = forwardedFor.split(",")[0].trim();
      if (!first.isEmpty()) {
        return first;
      }
    }
    return request.getRemoteAddr();
  }
}
