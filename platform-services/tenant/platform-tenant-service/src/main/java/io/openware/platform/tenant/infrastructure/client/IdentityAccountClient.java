package io.openware.platform.tenant.infrastructure.client;

import io.openware.common.exception.ApiException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** identity 账号内部查询；平台角色授予前校验账号类型。 */
@Component
public class IdentityAccountClient {

  private final RestClient restClient;

  public IdentityAccountClient(
      @Value("${app.identity-service.base-url:http://platform-identity-service:4100}") String baseUrl) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
  }

  public AccountInfo get(Long accountId) {
    try {
      AccountInfo info = restClient.get().uri("/internal/identity/accounts/{id}", accountId)
          .retrieve().body(AccountInfo.class);
      if (info == null) {
        throw new ApiException(404, "ACCOUNT_NOT_FOUND", "账号不存在");
      }
      return info;
    } catch (ApiException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ApiException(400, "ACCOUNT_LOOKUP_FAILED", "账号查询失败");
    }
  }

  public record AccountInfo(Long id, String accountType, String status) {
  }

  /** 平台运营账号（identity PLATFORM_OPERATOR）列表。 */
  public List<PlatformOperatorAccount> listPlatformOperators() {
    try {
      List<PlatformOperatorAccount> accounts = restClient.get()
          .uri("/internal/identity/accounts/platform-operators")
          .retrieve().body(new ParameterizedTypeReference<>() {
          });
      return accounts == null ? List.of() : accounts;
    } catch (RuntimeException exception) {
      throw new ApiException(400, "ACCOUNT_LOOKUP_FAILED", "平台运营账号查询失败");
    }
  }

  public record PlatformOperatorAccount(Long id, String loginIdentifier, String status) {
  }
}
