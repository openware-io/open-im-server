package com.gvchat.im.accessws.interceptor;

import com.gvchat.infrastructure.security.AuthenticationUserProjection;
import com.gvchat.common.constant.RedisKeys;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * WebSocket 握手阶段 JWT 认证拦截器，校验 token 并将用户信息写入会话属性。
 * 业务域：即时通讯 WebSocket 连接安全认证。
 */
@Component
@Slf4j
public class WebSocketAuthInterceptor implements HandshakeInterceptor {
  private final AuthenticationUserProjection authenticationUserProjection;
  private final StringRedisTemplate redisTemplate;

  /**
   * 构造认证拦截器。
   *
   * @param jwtTokenProvider JWT 令牌解析。
   */
  public WebSocketAuthInterceptor(
      AuthenticationUserProjection authenticationUserProjection,
      StringRedisTemplate redisTemplate) {
    this.authenticationUserProjection = authenticationUserProjection;
    this.redisTemplate = redisTemplate;
  }

  /**
   * 握手前从 URL 查询参数提取 JWT，校验用户身份。
   *
   * @param request  HTTP 请求
   * @param response  HTTP 响应（认证失败时设置 401。
   * @param wsHandler WebSocket 处理。
   * @param attributes 握手成功后写userId、username 的会话属。
   * @return 认证通过返回 {@code true}，否则返{@code false}
   */
  @Override
  public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                  WebSocketHandler wsHandler, Map<String, Object> attributes) {
    String query = request.getURI().getQuery();
    if (query == null || !query.contains("ticket=")) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    String ticket = query.substring(query.indexOf("ticket=") + 7);
    int amp = ticket.indexOf('&');
    if (amp > 0) ticket = ticket.substring(0, amp);
    try {
      String encodedTicket = redisTemplate.opsForValue().getAndDelete(RedisKeys.WS_TICKET + ticket);
      if (encodedTicket == null) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
      }
      String[] ticketFields = encodedTicket.split("\\|", -1);
      if (ticketFields.length != 2) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
      }
      Long userId = Long.parseLong(ticketFields[0]);
      long authenticationVersion = Long.parseLong(ticketFields[1]);
      var snapshot = authenticationUserProjection.findByUserId(userId);
      if (snapshot.isEmpty() || !snapshot.get().active()
          || snapshot.get().authenticationVersion() != authenticationVersion) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
      }
      attributes.put("userId", userId);
      attributes.put("username", snapshot.get().username());
      return true;
    } catch (Exception e) {
      log.warn(
          "WebSocket 握手认证失败, path={}",
          request.getURI().getPath(),
          e);
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
  }

  /**
   * 握手完成后的回调，当前无额外处理逻辑。
   *
   * @param request  HTTP 请求
   * @param response HTTP 响应
   * @param wsHandler WebSocket 处理。
   * @param exception 握手过程中抛出的异常，无异常时为 {@code null}
   */
  @Override
  public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                WebSocketHandler wsHandler, Exception exception) {
    if (exception != null) {
      log.warn("WebSocket 握手异常结束, path={}", request.getURI().getPath(), exception);
    }
  }
}
