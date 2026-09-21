package com.gvchat.gateway.ratelimit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class RateLimitFilterTest {
  private final RateLimitProperties properties = new RateLimitProperties();
  private final GatewayFilterChain chain = mock(GatewayFilterChain.class);

  @BeforeEach
  void setUp() {
    when(chain.filter(any())).thenReturn(Mono.empty());
  }

  private RateLimitFilter newFilter(ReactiveStringRedisTemplate redis) {
    RemoteAddressResolver resolver = mock(RemoteAddressResolver.class);
    when(resolver.resolve(any())).thenReturn(new InetSocketAddress("127.0.0.1", 0));
    return new RateLimitFilter(properties, redis, null, resolver);
  }

  private ServerWebExchange exchange(String path, HttpMethod method) {
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    when(request.getURI()).thenReturn(URI.create(path));
    when(request.getMethod()).thenReturn(method);
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    when(response.getHeaders()).thenReturn(new HttpHeaders());
    ServerWebExchange exchange = mock(ServerWebExchange.class);
    when(exchange.getRequest()).thenReturn(request);
    when(exchange.getResponse()).thenReturn(response);
    return exchange;
  }

  private ReactiveStringRedisTemplate redisReturning(List<Long> result) {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    when(redis.execute(ArgumentMatchers.<RedisScript<List<Long>>>any(), ArgumentMatchers.<String>anyList(), anyList())).thenReturn(Flux.just(result));
    return redis;
  }

  @Test
  void passesThroughWhenDisabled() {
    properties.setEnabled(false);
    ServerWebExchange exchange = exchange("/api/v1/messages", HttpMethod.GET);
    newFilter(null).filter(exchange, chain).block();
    verify(chain).filter(exchange);
  }

  @Test
  void passesThroughForNonApiPath() {
    ServerWebExchange exchange = exchange("/ws/im/v1", HttpMethod.GET);
    newFilter(null).filter(exchange, chain).block();
    verify(chain).filter(exchange);
  }

  @Test
  void passesThroughForCorsPreflight() {
    ServerWebExchange exchange = exchange("/api/v1/messages", HttpMethod.OPTIONS);
    newFilter(null).filter(exchange, chain).block();
    verify(chain).filter(exchange);
  }

  @Test
  void passesThroughWhenBucketDisabledByZeroLimit() {
    properties.setReadRequestsPerMinute(0);
    ServerWebExchange exchange = exchange("/api/v1/messages", HttpMethod.GET);
    newFilter(null).filter(exchange, chain).block();
    verify(chain).filter(exchange);
  }

  @Test
  void allowsWhenTokenBucketHasCapacity() {
    ServerWebExchange exchange = exchange("/api/v1/messages", HttpMethod.GET);
    newFilter(redisReturning(List.of(1L, 0L))).filter(exchange, chain).block();
    verify(chain).filter(exchange);
  }

  /** 公共媒体图片是静态对象读取：改造前 /media-public 不经过本过滤器，迁到 /api 后必须保持不限流。 */
  @Test
  void passesThroughPublicMediaReadsWithoutThrottling() {
    ServerWebExchange exchange = exchange("/api/v1/media-public/gv-media-public/saas/t100/202609/a.png", HttpMethod.GET);
    newFilter(null).filter(exchange, chain).block();
    verify(chain).filter(exchange);
  }

  @Test
  void stillThrottlesNonReadMethodsOnPublicMediaPath() {
    ServerWebExchange exchange = exchange("/api/v1/media-public/gv-media-public/a.png", HttpMethod.POST);
    ServerHttpResponse response = exchange.getResponse();
    DataBufferFactory bufferFactory = mock(DataBufferFactory.class);
    when(response.bufferFactory()).thenReturn(bufferFactory);
    when(bufferFactory.wrap(any(byte[].class))).thenReturn(mock(DataBuffer.class));
    when(response.writeWith(ArgumentMatchers.<Mono<DataBuffer>>any())).thenReturn(Mono.empty());

    newFilter(redisReturning(List.of(0L, 0L))).filter(exchange, chain).block();

    verify(response).setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
    verify(chain, never()).filter(exchange);
  }

  @Test
  void rejectsWhenTokenBucketIsExhausted() {
    ServerWebExchange exchange = exchange("/api/v1/messages", HttpMethod.POST);
    ServerHttpResponse response = exchange.getResponse();
    DataBufferFactory bufferFactory = mock(DataBufferFactory.class);
    when(response.bufferFactory()).thenReturn(bufferFactory);
    when(bufferFactory.wrap(any(byte[].class))).thenReturn(mock(DataBuffer.class));
    when(response.writeWith(ArgumentMatchers.<Mono<DataBuffer>>any())).thenReturn(Mono.empty());

    newFilter(redisReturning(List.of(0L, 0L))).filter(exchange, chain).block();

    verify(response).setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
    verify(chain, never()).filter(exchange);
  }
}
