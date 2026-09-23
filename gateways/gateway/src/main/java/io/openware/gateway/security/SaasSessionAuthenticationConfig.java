package io.openware.gateway.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

@Configuration
public class SaasSessionAuthenticationConfig {
  @Bean
  SaasSessionAuthenticationFilter saasSessionAuthenticationFilter(
      ReactiveStringRedisTemplate redisTemplate) {
    return new SaasSessionAuthenticationFilter(redisTemplate);
  }

  /** 币种兜底响应头（规范 §3.5）：晚于会话过滤器执行，读取其转发的上下文 token。 */
  @Bean
  CurrencyResponseHeaderFilter currencyResponseHeaderFilter() {
    return new CurrencyResponseHeaderFilter();
  }

  /** 公共媒体 Content-Disposition 纠正：存量对象落成 attachment 时，「点开/预览」会被强制下载。 */
  @Bean
  MediaContentDispositionFilter mediaContentDispositionFilter() {
    return new MediaContentDispositionFilter();
  }
}
