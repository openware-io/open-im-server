package io.openware.platform.admin.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.infrastructure.security.InternalServiceAuthenticationInterceptor;
import java.net.http.HttpClient;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 媒体服务（common-media-service）内部图片接口客户端：图片是平台公共能力，BFF 只做转发与契约映射，
 * 不再持有对象存储凭据、也不再自行实现 MinIO 客户端。
 *
 * <p>内部调用方式与本服务对 platform-tenant-service 的调用一致：URL 命中 {@code /internal/**} 时由
 * {@link InternalServiceAuthenticationInterceptor} 自动补充 {@code X-IM-Service-*} 内部签名头
 * （service-name=platform-admin-service，媒体服务 expected-source 已包含该来源）。
 *
 * <p>请求体为图片原始字节，{@code Content-Type} 即图片 MIME，{@code biz}/{@code scope} 为查询参数，
 * 三者一并纳入内部签名。这里不使用 multipart：媒体服务的 {@code /internal/**} 由平台鉴权过滤器先读取
 * 请求体做 SHA-256 签名校验，容器 multipart 解析与签名读取会争用同一请求流，原始字节体是唯一可靠通道。
 *
 * <p>失败语义：媒体服务 400（类型不符/超大/空文件）原样透传 message；其余（401/403/5xx/连接失败）
 * 统一抛 503 {@code MEDIA_UPLOAD_FAILED}，与改造前的对外行为保持一致。
 */
@Slf4j
@Component
public class MediaServiceImageClient {

  private static final String UPLOAD_PATH = "/internal/media/images";
  private static final String UPLOAD_FAILED_CODE = "MEDIA_UPLOAD_FAILED";
  private static final String UPLOAD_FAILED_MESSAGE = "图片上传失败，请稍后重试";
  private static final String REJECTED_CODE = "MEDIA_UPLOAD_REJECTED";

  private final RestClient restClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public MediaServiceImageClient(
      @Value("${app.media-service.base-url:http://common-media-service:3600}") String baseUrl,
      InternalServiceAuthenticationInterceptor internalAuthInterceptor) {
    HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(5)).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofSeconds(30));
    this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory)
        .requestInterceptor(internalAuthInterceptor).build();
  }

  /** 上传图片字节到媒体服务，返回公开 URL 与对象键。 */
  public UploadedImage upload(String biz, String scope, String contentType, byte[] content) {
    try {
      String response = restClient.post()
          .uri(uriBuilder -> {
            uriBuilder.path(UPLOAD_PATH);
            if (biz != null && !biz.isBlank()) {
              uriBuilder.queryParam("biz", biz);
            }
            if (scope != null && !scope.isBlank()) {
              uriBuilder.queryParam("scope", scope);
            }
            return uriBuilder.build();
          })
          .contentType(mediaType(contentType))
          .body(content == null ? new byte[0] : content)
          .retrieve()
          .onStatus(status -> status.value() == HttpStatusCodes.BAD_REQUEST,
              (request, errorResponse) -> { throw rejected(errorResponse); })
          .onStatus(status -> status.isError(),
              (request, errorResponse) -> { throw unavailable(errorResponse); })
          .body(String.class);
      return parse(response);
    } catch (ApiException exception) {
      throw exception;
    } catch (Exception exception) {
      log.warn("调用媒体服务图片上传失败: biz={}, scope={}, contentType={}", biz, scope, contentType, exception);
      throw unavailable(null);
    }
  }

  /** 400：媒体服务是类型/大小/空文件的权威裁决者，原样透传其 code 与 message。 */
  private ApiException rejected(ClientHttpResponse response) {
    JsonNode node = errorBody(response);
    String code = node != null && node.hasNonNull("code") ? node.get("code").asText() : REJECTED_CODE;
    String message = node != null && node.hasNonNull("message") ? node.get("message").asText()
        : UPLOAD_FAILED_MESSAGE;
    return new ApiException(HttpStatusCodes.BAD_REQUEST, code, message);
  }

  /** 其余失败（内部鉴权被拒、5xx、不可用）统一按可重试的上传失败返回。 */
  private ApiException unavailable(ClientHttpResponse response) {
    if (response != null) {
      log.warn("媒体服务图片上传不可用: status={}, body={}", statusOf(response), errorText(response));
    }
    return new ApiException(HttpStatusCodes.SERVICE_UNAVAILABLE, UPLOAD_FAILED_CODE, UPLOAD_FAILED_MESSAGE);
  }

  private String statusOf(ClientHttpResponse response) {
    try {
      return String.valueOf(response.getStatusCode());
    } catch (Exception exception) {
      return "unknown";
    }
  }

  private UploadedImage parse(String response) {
    JsonNode node = readTree(response);
    if (node == null || !node.hasNonNull("url") || !node.hasNonNull("objectKey")) {
      throw unavailable(null);
    }
    return new UploadedImage(node.get("url").asText(), node.get("objectKey").asText(),
        node.hasNonNull("contentType") ? node.get("contentType").asText() : null, node.path("size").asLong());
  }

  private JsonNode errorBody(ClientHttpResponse response) {
    return readTree(errorText(response));
  }

  private String errorText(ClientHttpResponse response) {
    try {
      return new String(response.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    } catch (Exception exception) {
      return "";
    }
  }

  private JsonNode readTree(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readTree(body);
    } catch (Exception exception) {
      return null;
    }
  }

  /** 无法识别的声明类型按二进制处理，由媒体服务按白名单拒绝并返回 400。 */
  private MediaType mediaType(String contentType) {
    if (contentType == null || contentType.isBlank()) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
    try {
      return MediaType.parseMediaType(contentType);
    } catch (InvalidMediaTypeException exception) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }

  /** 媒体服务返回的图片信息（内部契约 DTO）。 */
  public record UploadedImage(String url, String objectKey, String contentType, long size) { }
}
