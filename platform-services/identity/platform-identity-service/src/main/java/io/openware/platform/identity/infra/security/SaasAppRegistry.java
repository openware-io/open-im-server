package io.openware.platform.identity.infra.security;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
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

  public SaasAppRegistry(
      @Value("${saas.apps.consumer:saas-a380-c,saas-hotel-c,saas-ktv-c}") String consumers,
      @Value("${saas.apps.backend:saas-a380-h5,saas-hotel-b,saas-ktv-b}") String backends) {
    this.consumers.addAll(split(consumers));
    this.backends.addAll(split(backends));
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
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
  }
}
