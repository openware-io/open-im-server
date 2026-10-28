package io.openware.platform.tenant.infrastructure.client;

import io.openware.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** identity 应用注册表内部查询；安装应用必须命中且 clientType 一致。 */
@Component
public class IdentityAppCatalogClient {

  private final RestClient restClient;

  public IdentityAppCatalogClient(
      @Value("${app.identity-service.base-url:http://platform-identity-service:4100}") String baseUrl) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public AppInfo get(String appId) {
    try {
      AppInfo info = restClient.get().uri("/internal/identity/apps/{appId}", appId)
          .retrieve().body(AppInfo.class);
      if (info == null || !"ACTIVE".equals(info.status())) {
        throw new ApiException(400, "APP_NOT_AVAILABLE", "应用未注册或不可用");
      }
      return info;
    } catch (ApiException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ApiException(400, "APP_NOT_AVAILABLE", "应用注册表不可用");
    }
  }

  public record AppInfo(String appId, String clientType, String status) {
  }
}
