package com.gvchat.im.admin.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.security.InternalServiceAuthentication;
import com.gvchat.infrastructure.security.InternalServiceSignature;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 审计查询客户端：调 common-audit-service 的内部查询端点 {@code POST /internal/audit/records/search}。
 *
 * <p>为什么复用「审计上报通道身份」而不是服务自身身份：common-audit-service 的
 * {@code internal.service-auth.expected-source} 白名单**只放审计上报通道**
 * （{@code gv-im-audit-reporter}），业务服务身份会被 401 拒绝；而审计服务端对内部通道的信任模型
 * 就是「持有内部共享密钥 + 声明该来源」。因此这里用与 {@link AuditClient} 相同的来源与签名算法，
 * 不新增白名单、不放松服务端校验。
 *
 * <p>查询走 POST + JSON 体（而不是 GET + query string）：内部签名覆盖查询串，GET 需要调用方
 * 与服务端对编码后的查询串逐字节一致，易碎；POST 只签路径 + 内容哈希，稳定。
 */
@Component
public class AuditQueryClient {
  private static final String SEARCH_PATH = "/internal/audit/records/search";
  private static final String JSON_CONTENT_TYPE = "application/json";
  /** 与 {@code AuditClientConfig} 的本地联调回退密钥保持一致（生产由环境变量覆盖）。 */
  private static final String DEV_FALLBACK_SECRET = "gv-im-audit-channel-dev-secret-0001";

  private final RestClient restClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String internalSecret;
  private final String source;

  public AuditQueryClient(
      @Value("${app.audit-service.base-url:http://localhost:4190}") String baseUrl,
      @Value("${internal.service-auth.secret:" + DEV_FALLBACK_SECRET + "}") String internalSecret,
      @Value("${app.audit-service.reporter-source:" + AuditClient.DEFAULT_REPORTER_SOURCE + "}") String reporterSource) {
    this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    this.internalSecret = internalSecret;
    this.source = reporterSource == null || reporterSource.isBlank()
        ? AuditClient.DEFAULT_REPORTER_SOURCE : reporterSource;
  }

  /** 按内部签名查询审计分页数据；失败抛异常，由调用方决定如何对外表达。 */
  public Map<String, Object> search(Map<String, Object> filters) {
    try {
      byte[] payload = objectMapper.writeValueAsBytes(filters);
      long timestamp = System.currentTimeMillis();
      String requestId = UUID.randomUUID().toString().replace("-", "");
      String contentHash = InternalServiceSignature.contentHash(payload);
      String signature = InternalServiceSignature.sign(internalSecret, "POST", SEARCH_PATH, null,
          JSON_CONTENT_TYPE, contentHash, source, requestId, timestamp);
      String response = restClient.post().uri(SEARCH_PATH)
          .header(InternalServiceAuthentication.SOURCE_HEADER, source)
          .header(InternalServiceAuthentication.VERSION_HEADER,
              InternalServiceAuthentication.AUTHENTICATION_VERSION)
          .header(InternalServiceAuthentication.REQUEST_ID_HEADER, requestId)
          .header(InternalServiceAuthentication.CONTENT_SHA256_HEADER, contentHash)
          .header(InternalServiceAuthentication.TIMESTAMP_HEADER, Long.toString(timestamp))
          .header(InternalServiceAuthentication.SIGNATURE_HEADER, signature)
          .contentType(MediaType.APPLICATION_JSON)
          .body(payload)
          .retrieve()
          .body(String.class);
      if (response == null || response.isBlank()) {
        return Map.of();
      }
      return objectMapper.readValue(response, new TypeReference<Map<String, Object>>() {});
    } catch (Exception exception) {
      throw new IllegalStateException("查询审计日志失败", exception);
    }
  }
}
