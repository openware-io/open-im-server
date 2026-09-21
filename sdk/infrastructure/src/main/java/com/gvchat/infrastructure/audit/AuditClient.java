package com.gvchat.infrastructure.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.infrastructure.security.InternalServiceAuthentication;
import com.gvchat.infrastructure.security.InternalServiceSignature;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 通用审计客户端：调 common-audit-service 的 {@code POST /internal/audit/records}（单条）
 * 与 {@code POST /internal/audit/records/batch}（批量）写审计。
 *
 * <p>平台化口径（SAAS_PLATFORM_02 §9.4 / SAAS_PLATFORM_06 §8/§12）：
 * <ul>
 *   <li>出站请求按 {@link InternalServiceAuthentication} 同一实现做 HMAC 签名，服务端开启强校验后不会 401；</li>
 *   <li>租户/组织/门店/操作人默认从当前请求的租户上下文补全，IP/UA/requestId/traceId 从当前 HTTP 请求补全，
 *       各调用点只需声明动作与业务对象，避免「谁忘了填 tenantId 就变成 0 号租户」；</li>
 *   <li>{@code detail_json} 统一走 {@link AuditDetailSanitizer} 脱敏（密码/token/密钥/手机号/证件号）并保证是合法 JSON；</li>
 *   <li>失败只告警不阻塞业务：{@code record}/{@code recordBatch} 抛异常由调用方决定是否拒绝业务，
 *       {@code recordAsync} 只写 WARN（不静默）。</li>
 * </ul>
 */
@Slf4j
public class AuditClient {

  /** 审计上报通道的固定服务身份：写审计的能力不与业务服务身份混用。 */
  public static final String DEFAULT_REPORTER_SOURCE = "gv-im-audit-reporter";

  /** 单次批量上报条数上限，与服务端校验保持一致。 */
  public static final int MAX_BATCH_SIZE = 200;

  private static final String SINGLE_PATH = "/internal/audit/records";
  private static final String BATCH_PATH = "/internal/audit/records/batch";
  private static final String JSON_CONTENT_TYPE = "application/json";
  private static final String REQUEST_ID_HEADER = "X-Request-Id";
  private static final String TRACE_ID_HEADER = "X-Trace-Id";
  private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

  private final RestClient restClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String internalSecret;
  private final String source;
  private final boolean enabled;

  public AuditClient(RestClient restClient, String internalSecret) {
    this(restClient, internalSecret, DEFAULT_REPORTER_SOURCE, true);
  }

  public AuditClient(RestClient restClient, String internalSecret, String source) {
    this(restClient, internalSecret, source, true);
  }

  private AuditClient(RestClient restClient, String internalSecret, String source, boolean enabled) {
    this.restClient = restClient;
    this.internalSecret = internalSecret;
    this.source = source == null || source.isBlank() ? DEFAULT_REPORTER_SOURCE : source;
    this.enabled = enabled;
  }

  /**
   * 关闭状态的客户端：只用于「兼容既有构造器」的装配路径（单测直接 new 出业务类）。
   *
   * <p>生产装配一律走 {@code AuditClientConfig} 注入真实客户端；关闭状态不会出现在 Spring 容器里，
   * 因此不存在「悄悄不上报」的运行期风险。
   */
  public static AuditClient disabled() {
    return new AuditClient(null, null, DEFAULT_REPORTER_SOURCE, false);
  }

  /** 同步写审计：返回审计记录 ID；失败抛异常，由调用方决定是否拒绝业务（资金/授权等高风险路径使用）。 */
  public Long record(AuditRecord record) {
    if (!enabled) {
      return null;
    }
    Map<String, Object> body = buildBody(record);
    Map<String, Object> response = post(SINGLE_PATH, body);
    return longValue(response.get("id"));
  }

  /**
   * 异步写审计：fire-and-forget，失败仅告警不阻塞业务。
   *
   * <p>上下文补全（租户/操作人/IP/UA）在**调用线程**完成，异步线程只负责网络投递，
   * 否则 ThreadLocal 上下文丢失会把审计写到 0 号租户。
   */
  public CompletableFuture<Long> recordAsync(AuditRecord record) {
    if (!enabled) {
      return CompletableFuture.completedFuture(null);
    }
    Map<String, Object> body = buildBody(record);
    return CompletableFuture.supplyAsync(() -> longValue(post(SINGLE_PATH, body).get("id")))
        .exceptionally(e -> {
          // 只打 e.getMessage() 会丢掉真正的原因（post 一律包装成 IllegalStateException），
          // 运维拿不到状态码/响应体就无法判断是鉴权、校验还是网络问题——这里打到根因。
          log.warn("异步写审计失败, action={}, resourceType={}, resourceId={}, cause={}",
              record.action(), record.resourceType(), record.resourceId(), rootCause(e));
          return null;
        });
  }

  /** 取异常链最内层：HTTP 状态码与响应体都在最里层异常上。 */
  private static Throwable rootCause(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  /**
   * 批量写审计：一次请求写入多条（上限 {@link #MAX_BATCH_SIZE}），返回服务端接受的条数。
   *
   * <p>批量用于运营上下文切换、批量导入等一次产生多条留痕的场景；超过上限直接抛
   * {@link IllegalArgumentException}，由调用方拆分，不做静默截断。
   */
  public int recordBatch(List<AuditRecord> records) {
    if (!enabled || records == null || records.isEmpty()) {
      return 0;
    }
    if (records.size() > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException("批量上报审计条数超限: " + records.size() + " > " + MAX_BATCH_SIZE);
    }
    List<Map<String, Object>> items = new ArrayList<>(records.size());
    for (AuditRecord record : records) {
      items.add(buildBody(record));
    }
    Map<String, Object> response = post(BATCH_PATH, Map.of("records", items));
    Object accepted = response.get("accepted");
    return accepted instanceof Number number ? number.intValue() : records.size();
  }

  /**
   * 组装上报报文：显式字段优先，缺省时从租户上下文与当前 HTTP 请求补全，并统一脱敏与限长。
   *
   * <p>公开是为了让各服务的回归测试能断言「最终上报报文里到底有没有操作人/租户」——
   * 例如平台级动作（{@code tenant.create}）必须能从平台作用域上下文补出 {@code operatorId}。
   * 业务代码不需要直接调用它，{@link #record}/{@link #recordAsync}/{@link #recordBatch} 已内聚该步骤。
   */
  public Map<String, Object> buildBody(AuditRecord record) {
    TenantContext context = TenantContextHolder.get();
    RequestInfo request = currentRequest();
    long tenantId = record.tenantId() == null
        ? (context == null ? 0L : context.tenantId())
        : record.tenantId();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenantId", tenantId);
    body.put("organizationId", record.organizationId() != null ? record.organizationId()
        : (context == null ? null : context.organizationId()));
    body.put("storeId", record.storeId() != null ? record.storeId()
        : (context == null ? null : context.storeId()));
    body.put("operatorId", record.operatorId() != null ? record.operatorId()
        : (context == null ? null : context.accountId()));
    body.put("operatorName", record.operatorName());
    body.put("operatorAccount", record.operatorAccount());
    body.put("operatorType", record.operatorType() == null ? defaultOperatorType(context)
        : record.operatorType());
    body.put("action", record.action() == null ? "" : record.action());
    body.put("actionLabel", record.actionLabel() == null ? AuditActions.labelOf(record.action())
        : record.actionLabel());
    body.put("resourceType", record.resourceType());
    body.put("resourceId", record.resourceId());
    body.put("resourceName", record.resourceName());
    body.put("result", record.result() == null ? AuditRecord.RESULT_SUCCESS : record.result());
    body.put("errorCode", record.errorCode());
    body.put("ip", record.ip() != null ? record.ip() : request == null ? null : request.remoteAddress());
    body.put("userAgent", record.userAgent() != null ? record.userAgent()
        : request == null ? null : request.userAgent());
    body.put("requestId", record.requestId() != null ? record.requestId()
        : request == null ? null : request.requestId());
    body.put("traceId", record.traceId() != null ? record.traceId()
        : request == null ? null : request.traceId());
    body.put("idempotencyKey", record.idempotencyKey());
    body.put("detailJson", AuditDetailSanitizer.sanitize(record.detailJson()));
    body.put("occurredAt", record.occurredAt() == null ? null : record.occurredAt().toString());
    return body;
  }

  /**
   * 缺省操作人类型：平台作用域上下文（平台运营）记 PLATFORM，其余记 TENANT。
   *
   * <p>平台级动作（如 {@code tenant.create}）由 {@code /api/v1/admin/platform/**} 下的领域服务上报，
   * 该路由的上下文由网关注入平台作用域签名 token，这里据此自动归类，调用点不必逐个声明。
   */
  private static String defaultOperatorType(TenantContext context) {
    return context != null && context.platformScope()
        ? AuditRecord.OPERATOR_TYPE_PLATFORM
        : AuditRecord.OPERATOR_TYPE_TENANT;
  }

  /** 按鉴权版本 2 组装签名头并投递；服务端强校验失败时抛异常，由调用方/WARN 处理。 */
  private Map<String, Object> post(String path, Object body) {
    try {
      byte[] payload = objectMapper.writeValueAsBytes(body);
      long timestamp = System.currentTimeMillis();
      String requestId = UUID.randomUUID().toString().replace("-", "");
      String contentHash = InternalServiceSignature.contentHash(payload);
      String signature = InternalServiceSignature.sign(internalSecret, "POST", path, null, JSON_CONTENT_TYPE,
          contentHash, source, requestId, timestamp);
      String response = restClient.post()
          .uri(path)
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
      return objectMapper.readValue(response, new com.fasterxml.jackson.core.type.TypeReference<>() { });
    } catch (Exception e) {
      throw new IllegalStateException("写审计失败(path=" + path + ")", e);
    }
  }

  private RequestInfo currentRequest() {
    if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
      return null;
    }
    HttpServletRequest request = attributes.getRequest();
    String requestId = header(request, REQUEST_ID_HEADER);
    if (requestId == null) {
      requestId = request.getHeader("X-Request-ID");
    }
    return new RequestInfo(remoteAddress(request), request.getHeader("User-Agent"), requestId,
        header(request, TRACE_ID_HEADER));
  }

  private String remoteAddress(HttpServletRequest request) {
    String forwarded = request.getHeader(FORWARDED_FOR_HEADER);
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }

  private static String header(HttpServletRequest request, String name) {
    String value = request.getHeader(name);
    return value == null || value.isBlank() ? null : value;
  }

  private static Long longValue(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  /** 当前请求的可审计元数据（由调用线程采集，避免异步线程丢失上下文）。 */
  private record RequestInfo(String remoteAddress, String userAgent, String requestId, String traceId) { }

  /**
   * 审计记录：字段与本服务 {@code POST /internal/audit/records} 入参一一对应。
   *
   * <p>{@link #of} 保留既有 8 参调用口径（动作 + 业务对象为主），新增字段用 {@link #builder()} 表达。
   */
  public record AuditRecord(
      Long tenantId, Long organizationId, Long storeId,
      Long operatorId, String operatorName, String operatorAccount, String operatorType,
      String action, String actionLabel, String resourceType, String resourceId, String resourceName,
      String result, String errorCode, String ip, String userAgent, String requestId, String traceId,
      String idempotencyKey, String detailJson, LocalDateTime occurredAt) {

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAILURE = "FAILURE";
    public static final String OPERATOR_TYPE_PLATFORM = "PLATFORM";
    public static final String OPERATOR_TYPE_TENANT = "TENANT";

    /** 既有调用口径：{@code (tenantId, operatorId, action, resourceType, resourceId, requestId, idempotencyKey, detailJson)}。 */
    public static AuditRecord of(Long tenantId, Long operatorId, String action, String resourceType,
                                 String resourceId, String requestId, String idempotencyKey, String detailJson) {
      return builder().tenantId(tenantId).operatorId(operatorId).action(action).resourceType(resourceType)
          .resourceId(resourceId).requestId(requestId).idempotencyKey(idempotencyKey).detailJson(detailJson).build();
    }

    /** 失败留痕：业务失败也必须落一条 FAILURE 记录，不能只留成功路径。 */
    public static AuditRecord failure(Long tenantId, Long operatorId, String action, String actionLabel,
                                      String resourceType, String resourceId, String errorCode, String detailJson) {
      return builder().tenantId(tenantId).operatorId(operatorId).action(action).actionLabel(actionLabel)
          .resourceType(resourceType).resourceId(resourceId).result(RESULT_FAILURE).errorCode(errorCode)
          .detailJson(detailJson).build();
    }

    public static Builder builder() {
      return new Builder();
    }

    /** 审计记录构造器：只暴露显式字段，缺省值由 {@link AuditClient#buildBody} 补全。 */
    public static final class Builder {
      private Long tenantId;
      private Long organizationId;
      private Long storeId;
      private Long operatorId;
      private String operatorName;
      private String operatorAccount;
      private String operatorType;
      private String action;
      private String actionLabel;
      private String resourceType;
      private String resourceId;
      private String resourceName;
      private String result;
      private String errorCode;
      private String ip;
      private String userAgent;
      private String requestId;
      private String traceId;
      private String idempotencyKey;
      private String detailJson;
      private LocalDateTime occurredAt;

      public Builder tenantId(Long value) {
        this.tenantId = value;
        return this;
      }

      public Builder organizationId(Long value) {
        this.organizationId = value;
        return this;
      }

      public Builder storeId(Long value) {
        this.storeId = value;
        return this;
      }

      public Builder operatorId(Long value) {
        this.operatorId = value;
        return this;
      }

      public Builder operatorName(String value) {
        this.operatorName = value;
        return this;
      }

      public Builder operatorAccount(String value) {
        this.operatorAccount = value;
        return this;
      }

      public Builder operatorType(String value) {
        this.operatorType = value;
        return this;
      }

      public Builder action(String value) {
        this.action = value;
        return this;
      }

      public Builder actionLabel(String value) {
        this.actionLabel = value;
        return this;
      }

      public Builder resourceType(String value) {
        this.resourceType = value;
        return this;
      }

      public Builder resourceId(String value) {
        this.resourceId = value;
        return this;
      }

      public Builder resourceName(String value) {
        this.resourceName = value;
        return this;
      }

      public Builder result(String value) {
        this.result = value;
        return this;
      }

      public Builder errorCode(String value) {
        this.errorCode = value;
        return this;
      }

      public Builder ip(String value) {
        this.ip = value;
        return this;
      }

      public Builder userAgent(String value) {
        this.userAgent = value;
        return this;
      }

      public Builder requestId(String value) {
        this.requestId = value;
        return this;
      }

      public Builder traceId(String value) {
        this.traceId = value;
        return this;
      }

      public Builder idempotencyKey(String value) {
        this.idempotencyKey = value;
        return this;
      }

      public Builder detailJson(String value) {
        this.detailJson = value;
        return this;
      }

      public Builder occurredAt(LocalDateTime value) {
        this.occurredAt = value;
        return this;
      }

      public AuditRecord build() {
        return new AuditRecord(tenantId, organizationId, storeId, operatorId, operatorName, operatorAccount,
            operatorType, action, actionLabel, resourceType, resourceId, resourceName, result, errorCode, ip,
            userAgent, requestId, traceId, idempotencyKey, detailJson, occurredAt);
      }
    }
  }
}
