package io.openware.platform.identity.infra.security;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SaaS OAuth 应用注册表（C/B 分离）。
 *
 * <p>appId 必须显式登记为 consumer 或 backend；禁止用后缀猜测 C/B。
 */
@Component
public class SaasAppRegistry {

  public enum ClientType { C, B }

  private final Set<String> consumers = new LinkedHashSet<>();
  private final Set<String> backends = new LinkedHashSet<>();
  private final Set<String> consumerReferers = new LinkedHashSet<>();
  private final Set<String> backendReferers = new LinkedHashSet<>();

  @Autowired
  public SaasAppRegistry(
      @Value("${saas.apps.consumer:}") String consumers,
      @Value("${saas.apps.backend:}") String backends,
      @Value("${saas.apps.consumer-referers:http://127.0.0.1:30080/c/,http://localhost:30080/c/,http://192.168.3.35:30080/c/}") String consumerReferers,
      @Value("${saas.apps.backend-referers:http://127.0.0.1:30080/saas/,http://localhost:30080/saas/,http://192.168.3.35:30080/saas/,http://127.0.0.1:30080/b/,http://localhost:30080/b/,http://192.168.3.35:30080/b/}") String backendReferers) {
    this.consumers.addAll(split(consumers));
    this.backends.addAll(split(backends));
    this.consumerReferers.addAll(split(consumerReferers));
    this.backendReferers.addAll(split(backendReferers));
    Set<String> overlap = new LinkedHashSet<>(this.consumers);
    overlap.retainAll(this.backends);
    if (!overlap.isEmpty()) {
      throw new IllegalStateException("SaaS app registered as both C and B: " + overlap);
    }
  }

  public SaasAppRegistry(String consumers, String backends) {
    this(consumers, backends,
        "http://127.0.0.1:30080/c/,http://localhost:30080/c/",
        "http://127.0.0.1:30080/saas/,http://localhost:30080/saas/,http://127.0.0.1:30080/b/,http://localhost:30080/b/");
  }

  /** 浏览器请求的 Referer 必须是精确 origin+path 白名单；带会话但无 Referer 时 fail-closed。 */
  public void requireReferer(String appId, String referer, boolean hasSessionCookie) {
    if (referer == null || referer.isBlank()) {
      if (hasSessionCookie) {
        throw new ApiException(HttpStatusCodes.FORBIDDEN, "CLIENT_ORIGIN_REQUIRED",
            "缺少请求来源");
      }
      return;
    }
    boolean backend = requireType(appId) == ClientType.B;
    URI actual;
    try {
      actual = URI.create(referer);
    } catch (IllegalArgumentException exception) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "CLIENT_ORIGIN_FORBIDDEN", "请求来源非法");
    }
    boolean allowed = (backend ? backendReferers : consumerReferers).stream().anyMatch(prefix -> {
      try {
        URI expected = URI.create(prefix);
        return actual.getScheme() != null && actual.getScheme().equalsIgnoreCase(expected.getScheme())
            && actual.getHost() != null && actual.getHost().equalsIgnoreCase(expected.getHost())
            && actual.getPort() == expected.getPort()
            && actual.getPath() != null && actual.getPath().startsWith(expected.getPath());
      // degradation: malformed configured prefix is ignored; it cannot authorize a request
      } catch (IllegalArgumentException ignored) {
        return false;
      }
    });
    if (!allowed) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "CLIENT_ORIGIN_FORBIDDEN",
          "请求来源与客户端族不一致");
    }
  }
  public ClientType requireType(String appId) {
    String normalized = appId == null ? "" : appId.trim();
    if (consumers.contains(normalized)) {
      return ClientType.C;
    }
    if (backends.contains(normalized)) {
      return ClientType.B;
    }
    throw new ApiException(HttpStatusCodes.BAD_REQUEST, "OAUTH_APP_INVALID", "未登记的 SaaS 应用");
  }

  public boolean isBackend(String appId) {
    return requireType(appId) == ClientType.B;
  }

  public String cookieName(String appId, boolean plain) {
    boolean backend = isBackend(appId);
    if (plain) {
      return backend ? SaasUserSessionCookie.B_PLAIN : SaasUserSessionCookie.C_PLAIN;
    }
    return backend ? SaasUserSessionCookie.B_NAME : SaasUserSessionCookie.C_NAME;
  }

  private static Set<String> split(String raw) {
    return Arrays.stream((raw == null ? "" : raw).split(","))
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }
}
