package com.gvchat.im.accessws.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
public class WebSocketContainerConfig {
  @Bean
  ServletServerContainerFactoryBean webSocketContainerFactoryBean(
      WebSocketSecurityProperties webSocketSecurityProperties) {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    container.setMaxTextMessageBufferSize(webSocketSecurityProperties.getMaxTextMessageBytes());
    container.setMaxBinaryMessageBufferSize(webSocketSecurityProperties.getMaxTextMessageBytes());
    return container;
  }
}
