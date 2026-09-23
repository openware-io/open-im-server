package io.openware.platform.admin.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.exception.ApiException;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预约领域服务客户端：BFF 转发到 platform-order-service /business/reservations，携带 X-Tenant-Context。
 * 响应统一按 String 取回再解析为 List/Map（避免 Spring Boot 4 消息转换器对 JsonNode 的序列化问题）。
 */
@Component
public class ReservationDomainClient {

  private final RestClient orderClient;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public ReservationDomainClient(@Value("${ORDER_SERVICE_BASE_URL:http://platform-order-service:4130}") String orderBaseUrl) {
    this.orderClient = RestClient.builder().baseUrl(orderBaseUrl).build();
  }

  /**
   * 预约列表：转发到 order 域 {@code GET /business/reservations}。
   *
   * <p>时间区间参数 {@code from}/{@code to} 原样透传（闭区间，域内按 {@code start_at} 过滤）；
   * 为空时**不带该查询参数**，域内按「不筛」处理（不发送空串）。
   */
  public List<Map<String, Object>> list(String from, String to) {
    return toList(getJson(withTimeRange("/business/reservations", from, to)));
  }

  /** 拼时间区间查询串：只在参数有值时附加，值与域名做 URL 编码（日期/时刻串本身安全，仍统一处理）。 */
  private static String withTimeRange(String uri, String from, String to) {
    StringBuilder query = new StringBuilder(uri);
    char separator = '?';
    if (from != null && !from.isBlank()) {
      query.append(separator).append("from=").append(URLEncoder.encode(from.trim(), StandardCharsets.UTF_8));
      separator = '&';
    }
    if (to != null && !to.isBlank()) {
      query.append(separator).append("to=").append(URLEncoder.encode(to.trim(), StandardCharsets.UTF_8));
    }
    return query.toString();
  }

  public Map<String, Object> confirm(Long id, Integer expectedVersion) {
    return toMap(postJson("/business/reservations/" + id + "/confirm",
        expectedVersion == null ? "{}" : "{\"expectedVersion\":" + expectedVersion + "}"));
  }

  public Map<String, Object> arrival(Long id) {
    return toMap(postJson("/business/reservations/" + id + "/arrival", "{}"));
  }

  /**
   * 分配包厢候选（只读）：转发到 order 域 GET /business/reservations/{id}/assignable-rooms。
   * 候选与不可分配原因由域内一次算完（房态 + 本时段预约冲突），BFF 不做二次拼接。
   */
  public List<Map<String, Object>> assignableRooms(Long id) {
    return toList(getJson("/business/reservations/" + id + "/assignable-rooms"));
  }

  /**
   * 取消预约：转发原因到 order 域（域内要求原因必填，空白会被 400 拒绝）。
   * 请求体走 ObjectMapper 组装：原因是运营自由文本，手工拼 JSON 遇到引号/换行会拼出非法报文。
   */
  public Map<String, Object> cancel(Long id, String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (reason != null && !reason.isBlank()) {
      body.put("reason", reason);
    }
    return toMap(postJson("/business/reservations/" + id + "/cancel", writeJson(body)));
  }

  /** 预约履约开台：ARRIVED/CONFIRMED 预约 → 生成订单 + KTV 包厢会话 + 回填 order_id（预约履约后生成订单）。 */
  public Map<String, Object> openTable(Long id) {
    return toMap(postJson("/business/reservations/" + id + "/open-table", "{}"));
  }

  /**
   * 标记未到店：转发到 order 域 POST /business/reservations/{id}/no-show（到店前且已过预约开始时间 → NO_SHOW）。
   * 域内负责状态校验（未到点 409 RESERVATION_NOT_STARTED）与审计 reservation.no_show。
   */
  public Map<String, Object> noShow(Long id) {
    return toMap(postJson("/business/reservations/" + id + "/no-show", "{}"));
  }

  /**
   * 到店分配具体包厢：转发到 order 域 POST /business/reservations/{id}/assign-room。
   * override=true 表示「改派已有包厢」（域内要求显式覆盖并留痕），false 时换包厢会被域内以 409 拒绝。
   */
  public Map<String, Object> assignRoom(Long id, Long resourceId, boolean override) {
    return toMap(postJson("/business/reservations/" + id + "/assign-room",
        "{\"resourceId\":" + resourceId + ",\"override\":" + override + "}"));
  }

  private JsonNode getJson(String uri) {
    try {
      String resp = orderClient.get()
          .uri(uri)
          .header("X-Tenant-Context", tenantContextJson())
          .retrieve()
          .body(String.class);
      return objectMapper.readTree(resp);
    } catch (RestClientResponseException e) {
      throw toApiException(e);
    } catch (Exception e) {
      throw new IllegalStateException("调用预约服务失败: " + uri, e);
    }
  }

  private JsonNode postJson(String uri, String body) {
    try {
      String resp = orderClient.post()
          .uri(uri)
          .header("X-Tenant-Context", tenantContextJson())
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .body(String.class);
      return objectMapper.readTree(resp);
    } catch (RestClientResponseException e) {
      throw toApiException(e);
    } catch (Exception e) {
      throw new IllegalStateException("调用预约服务失败: " + uri, e);
    }
  }

  /** 组装 JSON 请求体（自由文本一律走 ObjectMapper，避免手工拼串转义不全）。 */
  private String writeJson(Map<String, Object> body) {
    try {
      return objectMapper.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("组装预约请求体失败", e);
    }
  }

  private List<Map<String, Object>> toList(JsonNode node) {
    List<Map<String, Object>> result = new ArrayList<>();
    if (node != null && node.isArray()) {
      for (JsonNode item : node) {
        result.add(toMap(item));
      }
    }
    return result;
  }

  private Map<String, Object> toMap(JsonNode node) {
    return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
  }

  private String tenantContextJson() {
    String token = AdminContextHolder.get() == null ? null : AdminContextHolder.get().tenantContextToken();
    if (token == null || token.isBlank()) throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "请先选择经营上下文");
    return token;
  }

  private ApiException toApiException(RestClientResponseException e) {
    String code = null;
    String message = e.getStatusText();
    try {
      JsonNode node = objectMapper.readTree(e.getResponseBodyAsString());
      if (node.hasNonNull("code")) code = node.get("code").asText();
      if (node.hasNonNull("message")) message = node.get("message").asText();
    } catch (Exception ignored) {
    }
    return new ApiException(e.getStatusCode().value(), code, message);
  }
}
