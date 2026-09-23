package io.openware.im.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webflux.autoconfigure.WebFluxProperties;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 公共媒体路由回归：/api/v1/media-public/** 必须在网关命中，并且只剥掉 api/v1/media-public 三段，
 * 转发给对象存储的 /{bucket}/{objectKey}；旧的 /media-public/** 路由必须已删除。
 *
 * <p>断言直接使用网关自身的 PathRoutePredicateFactory 与 StripPrefixGatewayFilterFactory 跑真实请求路径，
 * 而不是只对 application.yml 做字符串匹配，确保 PathPattern 段数与剥段数量真的对得上。
 */
class GatewayPublicMediaRouteTest {

  private static final Path CONFIG = Path.of("src", "main", "resources", "application.yml");
  private static final String CANONICAL_URL = "/api/v1/media-public/open-media-public/saas/t100/202609/a.png";
  private static final String LEGACY_URL = "/media-public/open-media-public/saas/t100/202609/a.png";

  @Test
  void publicMediaRouteTargetsObjectStorageAndStripsThreeSegments() throws IOException {
    RouteEntry route = publicMediaRoute();

    assertEquals("${MEDIA_INTERNAL_ENDPOINT:http://localhost:9000}", route.uri());
    assertEquals(List.of("/api/v1/media-public/**"), route.pathPatterns());
    assertEquals(3, route.stripPrefixParts(), "必须剥掉 api/v1/media-public 三段");
  }

  @Test
  void canonicalUrlMatchesOnlyPublicMediaRouteAndForwardsBucketAndKey() throws IOException {
    List<RouteEntry> routes = routes();

    assertEquals(List.of("public-media"), matchingRouteIds(routes, CANONICAL_URL),
        "规范 URL 只能命中 public-media 一条路由");
    assertEquals("/open-media-public/saas/t100/202609/a.png",
        forwardedPath(exchange(CANONICAL_URL), publicMediaRoute().stripPrefixParts()));
  }

  @Test
  void legacyPrefixIsNoLongerRouted() throws IOException {
    assertTrue(matchingRouteIds(routes(), LEGACY_URL).isEmpty(),
        "旧 /media-public/** 路由必须删除，历史 URL 由 order/resource 迁移直接改写");
  }

  private static RouteEntry publicMediaRoute() throws IOException {
    return routes().stream().filter(route -> "public-media".equals(route.id())).findFirst()
        .orElseThrow(() -> new AssertionError("application.yml 缺少 public-media 路由"));
  }

  /** 按行解析路由块：id / uri / Path 谓词 / StripPrefix 段数；Spring 配置的缩进是固定的。 */
  private static List<RouteEntry> routes() throws IOException {
    List<RouteEntry> routes = new ArrayList<>();
    String id = null;
    String uri = null;
    List<String> pathPatterns = List.of();
    int stripPrefixParts = -1;
    for (String rawLine : Files.readAllLines(CONFIG)) {
      String line = rawLine.trim();
      if (line.startsWith("- id: ")) {
        if (id != null) {
          routes.add(new RouteEntry(id, uri, pathPatterns, stripPrefixParts));
        }
        id = line.substring("- id: ".length()).trim();
        uri = null;
        pathPatterns = List.of();
        stripPrefixParts = -1;
      } else if (id != null && line.startsWith("uri: ")) {
        uri = line.substring("uri: ".length()).trim();
      } else if (id != null && line.startsWith("- Path=")) {
        pathPatterns = Arrays.stream(line.substring("- Path=".length()).split(",")).map(String::trim).toList();
      } else if (id != null && line.startsWith("- StripPrefix=")) {
        stripPrefixParts = Integer.parseInt(line.substring("- StripPrefix=".length()).trim());
      }
    }
    if (id != null) {
      routes.add(new RouteEntry(id, uri, pathPatterns, stripPrefixParts));
    }
    return routes;
  }

  private static List<String> matchingRouteIds(List<RouteEntry> routes, String path) {
    List<String> matched = new ArrayList<>();
    for (RouteEntry route : routes) {
      if (!route.pathPatterns().isEmpty() && pathPredicate(route.pathPatterns()).test(exchange(path))) {
        matched.add(route.id());
      }
    }
    return matched;
  }

  private static Predicate<ServerWebExchange> pathPredicate(List<String> patterns) {
    PathRoutePredicateFactory.Config config = new PathRoutePredicateFactory.Config().setPatterns(patterns);
    return new PathRoutePredicateFactory(new WebFluxProperties()).apply(config);
  }

  private static ServerWebExchange exchange(String path) {
    return MockServerWebExchange.from(MockServerHttpRequest.get(path));
  }

  /** 用网关真实的 StripPrefix 过滤器把请求转发路径取出来，验证剥离后的形态。 */
  private static String forwardedPath(ServerWebExchange exchange, int parts) {
    StripPrefixGatewayFilterFactory.Config config = new StripPrefixGatewayFilterFactory.Config();
    config.setParts(parts);
    GatewayFilter filter = new StripPrefixGatewayFilterFactory().apply(config);
    AtomicReference<String> forwarded = new AtomicReference<>();
    filter.filter(exchange, forwardedExchange -> {
      forwarded.set(forwardedExchange.getRequest().getURI().getRawPath());
      return Mono.empty();
    }).block();
    return forwarded.get();
  }

  private record RouteEntry(String id, String uri, List<String> pathPatterns, int stripPrefixParts) { }
}
