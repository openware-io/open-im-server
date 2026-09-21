package com.gvchat.im.accessws.config;

import static java.util.Objects.requireNonNull;

import com.gvchat.im.accessws.handler.ImWebSocketHandler;
import com.gvchat.im.accessws.interceptor.WebSocketAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 端点注册与拦截器配置类。
 * 业务域：即时通讯 WebSocket 连接接入层。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
  private final ImWebSocketHandler imWebSocketHandler;
  private final WebSocketAuthInterceptor webSocketAuthInterceptor;
  private final WebSocketSecurityProperties webSocketSecurityProperties;

  /**
   * 构WebSocket 配置，注入消息处理器与认证拦截器。
   *
   * @param imWebSocketHandler    IM 消息处理。
   * @param webSocketAuthInterceptor WebSocket 握手认证拦截。
   */
  public WebSocketConfig(
      ImWebSocketHandler imWebSocketHandler,
      WebSocketAuthInterceptor webSocketAuthInterceptor,
      WebSocketSecurityProperties webSocketSecurityProperties) {
    this.imWebSocketHandler = imWebSocketHandler;
    this.webSocketAuthInterceptor = webSocketAuthInterceptor;
    this.webSocketSecurityProperties = webSocketSecurityProperties;
  }

  /**
   * 注册 WebSocket 处理器、认证拦截器及跨域策略。
   *
   * @param registry WebSocket 处理器注册表
   */
  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(requireNonNull(imWebSocketHandler), "/ws/im/v1")
        .addInterceptors(webSocketAuthInterceptor)
        .setAllowedOrigins(webSocketSecurityProperties.allowedOriginsArray());
  }
}
