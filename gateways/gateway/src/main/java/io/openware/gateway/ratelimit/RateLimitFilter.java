package io.openware.gateway.ratelimit;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ipresolver.RemoteAddressResolver;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Global filter that rate-limits the {@code /api/**} surface per client address.
 *
 * <p>Read and write traffic use separate token buckets so their allowances can
 * differ. The limiter fails open when Redis is unavailable so that an
 * infrastructure blip never blocks legitimate traffic.
 */
@Slf4j
public class RateLimitFilter implements GlobalFilter, Ordered {
  private static final int FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;
  /** 公共媒体对象读取前缀：与网关 public-media 路由（/api/v1/media-public/**）保持一致。 */
  private static final String PUBLIC_MEDIA_PREFIX = "/api/v1/media-public";
  // 错误码统一为 UPPER_SNAKE 字符串（与全仓 141 个业务码一致），不再用数字 429；
  // HTTP 状态码本身仍是 429，客户端按 code=RATE_LIMITED 分支即可。
  private static final String RATE_LIMITED_BODY =
      "{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁，请稍后再试\"}";

  private final RateLimitProperties properties;
  private final ReactiveStringRedisTemplate redisTemplate;
  private final DefaultRedisScript<List<Long>> rateLimitScript;
  private final RemoteAddressResolver addressResolver;

  public RateLimitFilter(
      RateLimitProperties properties,
      ReactiveStringRedisTemplate redisTemplate,
      DefaultRedisScript<List<Long>> rateLimitScript,
      RemoteAddressResolver addressResolver) {
    this.properties = properties;
    this.redisTemplate = redisTemplate;
    this.rateLimitScript = rateLimitScript;
    this.addressResolver = addressResolver;
  }

  @Override
  public int getOrder() {
    return FILTER_ORDER;
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    if (!properties.isEnabled()) {
      return chain.filter(exchange);
    }
    String path = exchange.getRequest().getURI().getPath();
    if (path == null || !path.startsWith("/api/")) {
      return chain.filter(exchange);
    }
    HttpMethod method = exchange.getRequest().getMethod();
    // CORS preflight must never be throttled; it never reaches a business route.
    if (method == HttpMethod.OPTIONS) {
      return chain.filter(exchange);
    }
    if (isPublicMediaRead(path, method)) {
      return chain.filter(exchange);
    }
    boolean read = method == HttpMethod.GET || method == HttpMethod.HEAD;
    int perMinute =
        read ? properties.getReadRequestsPerMinute() : properties.getWriteRequestsPerMinute();
    if (perMinute <= 0) {
      return chain.filter(exchange);
    }

    String clientKey = resolveClientKey(exchange);
    String bucket = read ? "read" : "write";
    String tokensKey = properties.getKeyPrefix() + ":" + bucket + ":" + clientKey;
    String timestampKey = tokensKey + ":ts";
    List<String> keys = List.of(tokensKey, timestampKey);
    List<String> args =
        List.of(
            Double.toString(perMinute / 60.0),
            Integer.toString(perMinute),
            Double.toString(System.currentTimeMillis() / 1000.0),
            "1");

    return redisTemplate
        .execute(rateLimitScript, keys, args)
        .next()
        .defaultIfEmpty(List.of(1L, 0L))
        .onErrorResume(
            error -> {
              log.warn(
                  "Rate limiter Redis unavailable, failing open for path={} key={}",
                  path,
                  clientKey,
                  error);
              return Mono.just(List.of(1L, 0L));
            })
        .flatMap(
            result -> {
              boolean allowed = !result.isEmpty() && result.get(0) == 1L;
              if (allowed) {
                return chain.filter(exchange);
              }
              return reject(exchange, perMinute);
            });
  }

  /**
   * 公共媒体对象读取不占用业务接口配额。
   *
   * <p>公共媒体改走 /api 命名空间后会被 {@code path.startsWith("/api/")} 一并纳入限流：一个列表页的几十张
   * 缩略图即可打满 /api 读配额，429 在页面上表现为「图片加载失败」，且与改造前 /media-public 不限流的行为不一致。
   * 这里恢复原语义：只豁免 GET/HEAD 读取，对象键含不可猜测 UUID 且响应带长期 Cache-Control，写方法仍照常限流。
   */
  private static boolean isPublicMediaRead(String path, HttpMethod method) {
    if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
      return false;
    }
    return path.equals(PUBLIC_MEDIA_PREFIX) || path.startsWith(PUBLIC_MEDIA_PREFIX + "/");
  }

  private String resolveClientKey(ServerWebExchange exchange) {
    InetSocketAddress resolved = addressResolver.resolve(exchange);
    if (resolved != null) {
      String host = resolved.getHostString();
      if (host != null && !host.isBlank() && !"0.0.0.0".equals(host)) {
        return host;
      }
    }
    InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
    if (remote != null && remote.getHostString() != null && !remote.getHostString().isBlank()) {
      return remote.getHostString();
    }
    return "unknown";
  }

  private Mono<Void> reject(ServerWebExchange exchange, int perMinute) {
    ServerHttpResponse response = exchange.getResponse();
    response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
    HttpHeaders headers = response.getHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    headers.set(HttpHeaders.RETRY_AFTER, "60");
    headers.set("X-RateLimit-Limit", Integer.toString(perMinute));
    DataBuffer buffer =
        response.bufferFactory().wrap(RATE_LIMITED_BODY.getBytes(StandardCharsets.UTF_8));
    return response.writeWith(Mono.just(buffer));
  }
}
