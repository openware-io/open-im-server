package io.openware.platform.identity.infra.security;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
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
      @Value("${saas.apps.consumer-referers:/a380/,/c/}") String consumerReferers,
      @Value("${saas.apps.backend-referers:/saas/,/b/}") String backendReferers) {
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
    this(consumers, backends, "/a380/,/c/", "/saas/,/b/");
  }

  /** 浏览器请求的 Referer 路径必须属于声明的客户端族；无 Referer 的非浏览器调用不拦截。 */
  public void requireReferer(String appId, String referer) {
    if (referer == null || referer.isBlank()) {
      return;
    }
    boolean backend = requireType(appId) == ClientType.B;
    boolean allowed = (backend ? backendReferers : consumerReferers).stream()
        .anyMatch(referer::contains);
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
