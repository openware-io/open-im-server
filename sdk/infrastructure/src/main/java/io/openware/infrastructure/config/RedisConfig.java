package io.openware.infrastructure.config;

import static java.util.Objects.requireNonNull;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Redis 相关 Bean 配置。
 * <p>
 * 提供字符串模板与消息监听容器，供缓存、Pub/Sub 等场景使用。
 * </p>
 */
@Configuration
public class RedisConfig {

  /**
   * 创建字符串类型的 Redis 操作模板。
   *
   * @param factory Redis 连接工厂
   * @return 配置好连接工厂的 {@link StringRedisTemplate}
   */
  @Bean
  public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
    return new StringRedisTemplate(requireNonNull(factory));
  }

  /**
   * 创建 Redis 消息监听容器，用于订阅频道消息。
   *
   * @param factory Redis 连接工厂
   * @return 已绑定连接工厂的监听容器
   */
  @Bean
  public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(requireNonNull(factory));
    return container;
  }
}
