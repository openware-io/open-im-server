package io.openware.infrastructure.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.nio.charset.Charset;

/**
 * JSON 响应统一编码规范：显式声明 UTF-8，避免部分客户端按 ISO-8859-1 误判中文乱码。
 *
 * <p>RFC 8259 规定 application/json 默认 UTF-8，但为消除客户端解码歧义，
 * 统一在 Content-Type 上追加 charset=UTF-8（application/json;charset=UTF-8）。
 * Spring Framework 7（Spring Boot 4）的 JSON converter 类名为
 * {@link JacksonJsonHttpMessageConverter}（替代旧的 MappingJackson2HttpMessageConverter），
 * 通过 Spring Boot AutoConfiguration 全局生效（依赖 SDK 的服务自动加载）。</p>
 *
 * <p><b>为什么用 {@code configureMessageConverters(ServerBuilder)} 而不是
 * {@code extendMessageConverters(List)}</b>：后者在 Spring Framework 7 已标记
 * 「过时且待删除」，而 {@code configureMessageConverters(List)} 这个重载同样已过时。
 * 未过时的替代是接收 {@link HttpMessageConverters.ServerBuilder} 的重载。
 * 语义与改造前**逐条一致**：{@code configureMessageConvertersList} 拿到的正是 Spring 默认装配的
 * 那个转换器列表（新式路径内部调用 {@code registerDefaults()}），
 * 与 {@code extendMessageConverters} 收到的是同一个 list，因此仍是「就地定制」而非「替换」。</p>
 */
@AutoConfiguration
@Slf4j
public class WebJsonConfig implements WebMvcConfigurer {

  @Override
  public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
    builder.configureMessageConvertersList(this::forceUtf8);
  }

  /** 对 Spring 默认装配的每个转换器就地设置 UTF-8 默认字符集。 */
  private void forceUtf8(List<HttpMessageConverter<?>> converters) {
    converters.forEach(converter -> {
      try {
        converter.getClass().getMethod("setDefaultCharset", Charset.class)
            .invoke(converter, StandardCharsets.UTF_8);
      } catch (ReflectiveOperationException ignored) {
        // 该转换器没有 setDefaultCharset：响应可能按容器默认字符集编码而出现中文乱码。
        // 这是本类要消除的编码契约缺陷，不能静默，故用 WARN 使其可观测。
        log.warn("converter {} has no setDefaultCharset(Charset); "
            + "response may fall back to the container default encoding",
            converter.getClass().getName(), ignored);
      }
    });
  }
}
