package com.gvchat.infrastructure.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * JSON 响应统一编码规范：显式声明 UTF-8，避免部分客户端按 ISO-8859-1 误判中文乱码。
 *
 * <p>RFC 8259 规定 application/json 默认 UTF-8，但为消除客户端解码歧义，
 * 统一在 Content-Type 上追加 charset=UTF-8（application/json;charset=UTF-8）。
 * Spring Framework 7（Spring Boot 4）的 JSON converter 类名为
 * {@link JacksonJsonHttpMessageConverter}（替代旧的 MappingJackson2HttpMessageConverter），
 * 通过 Spring Boot AutoConfiguration 全局生效（依赖 SDK 的服务自动加载）。</p>
 */
@AutoConfiguration
public class WebJsonConfig implements WebMvcConfigurer {

  @Override
  public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
    converters.forEach(converter -> {
      try {
        converter.getClass().getMethod("setDefaultCharset", java.nio.charset.Charset.class)
            .invoke(converter, StandardCharsets.UTF_8);
      } catch (ReflectiveOperationException ignored) {
      }
    });
  }
}
