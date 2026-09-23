package io.openware.platform.admin.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * IDaaS SSO 客户端：用一次性票据向集团 IDaaS 换取账号信息（服务端交换，不再在后台本地共享 jwt.secret 验签）。
 */
@Component
public class IdaasSsoClient {

  private final RestClient idaasClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public IdaasSsoClient(@Value("${IDAAS_SERVICE_BASE_URL:http://localhost:4220}") String idaasBaseUrl) {
    this.idaasClient = RestClient.builder().baseUrl(idaasBaseUrl).build();
  }

  /** 校验一次性 SSO 票据，返回账号信息。 */
  public SsoUser verifyTicket(String ticket) {
    try {
      String body = objectMapper.writeValueAsString(Map.of("ticket", ticket));
      String resp = idaasClient.post()
          .uri("/idaas/auth/sso/verify")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(String.class);
      var node = objectMapper.readTree(resp);
      return new SsoUser(node.path("accountId").asLong(),
          node.path("username").asText(""),
          node.path("valid").asBoolean(false),
          node.path("expiresAt").asLong(0));
    } catch (ApiException e) {
      throw e;
    } catch (Exception e) {
      throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "SSO_TICKET_INVALID", "SSO 票据无效或已过期");
    }
  }

  /** IDaaS 票据校验结果。 */
  public record SsoUser(long accountId, String username, boolean valid, long expiresAt) {
    public SsoUser(long accountId, String username, boolean valid) {
      this(accountId, username, valid, 0);
    }
  }
}
