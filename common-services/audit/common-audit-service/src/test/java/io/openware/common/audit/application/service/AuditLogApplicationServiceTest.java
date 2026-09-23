package io.openware.common.audit.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.openware.common.audit.api.dto.AuditBatchWriteResult;
import io.openware.common.audit.api.dto.AuditWriteResult;
import io.openware.common.audit.domain.model.AuditLog;
import io.openware.common.audit.domain.model.OperatorDisplay;
import io.openware.common.audit.domain.repository.AuditLogRepository;
import io.openware.common.exception.ApiException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 审计写入回归：幂等键口径、字段/枚举校验（非法 400 不 500）、批量边界、详情脱敏。
 */
class AuditLogApplicationServiceTest {

  private final InMemoryAuditLogRepository repository = new InMemoryAuditLogRepository();
  private final AuditLogApplicationService service = new AuditLogApplicationService(repository);

  @Test
  void writesRecordWithChineseLabelAndSanitizedDetail() throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenantId", 1001);
    body.put("operatorId", 77);
    body.put("action", "order.settle");
    body.put("resourceType", "ord_order");
    body.put("resourceId", "9");
    body.put("detailJson", "{\"password\":\"p@ss\",\"amount\":\"10.00\"}");

    AuditWriteResult result = service.write(body, "platform-admin-service");

    assertFalse(result.duplicated());
    AuditLog saved = repository.records.get(0);
    assertEquals("结台结算", saved.getActionLabel());
    assertEquals("platform-admin-service", saved.getSourceService());
    assertTrue(saved.getDetailJson().contains("[REDACTED]"));
    assertFalse(saved.getDetailJson().contains("p@ss"));
    assertEquals(1001L, saved.getTenantId());
    assertEquals(77L, saved.getOperatorId());
    assertTrue(new ObjectMapper().readTree(saved.getDetailJson()).path("amount").asText().equals("10.00"));
  }

  /** 显式幂等键重复上报只落一条，第二次返回 duplicated=true（网络重试/双写不能产生两条留痕）。 */
  @Test
  void explicitIdempotencyKeyKeepsSingleRecord() {
    Map<String, Object> body = writeBody("payment.refund.approve");
    body.put("idempotencyKey", "refund:9:approve");

    AuditWriteResult first = service.write(body, null);
    AuditWriteResult second = service.write(body, null);

    assertFalse(first.duplicated());
    assertTrue(second.duplicated());
    assertEquals(first.id(), second.id());
    assertEquals(1, repository.records.size());
  }

  /** 未给幂等键时按 (tenant, action, requestId, resource) 派生：同一请求重复上报只留一条。 */
  @Test
  void derivesIdempotencyKeyFromRequestId() {
    Map<String, Object> body = writeBody("order.item.add");
    body.put("requestId", "req-abc-123");

    AuditWriteResult first = service.write(body, null);
    AuditWriteResult second = service.write(body, null);

    assertFalse(first.duplicated());
    assertTrue(second.duplicated());
    assertEquals(1, repository.records.size());
  }

  /** 没有 requestId 时不得误合并两次真实发生的相同操作。 */
  @Test
  void withoutCorrelationIdEachWriteIsIndependent() {
    Map<String, Object> body = writeBody("order.item.add");

    service.write(body, null);
    service.write(body, null);

    assertEquals(2, repository.records.size());
  }

  @Test
  void rejectsInvalidEnumsAndLengthsWith400() {
    assertEquals(400, assertThrows(ApiException.class,
        () -> service.write(bodyWith("result", "FAILED"), null)).getStatus());
    assertEquals(400, assertThrows(ApiException.class,
        () -> service.write(bodyWith("operatorType", "ROBOT"), null)).getStatus());
    assertEquals(400, assertThrows(ApiException.class,
        () -> service.write(bodyWith("tenantId", "-1"), null)).getStatus());
    assertEquals(400, assertThrows(ApiException.class,
        () -> service.write(bodyWith("tenantId", "not-a-number"), null)).getStatus());
    assertEquals(400, assertThrows(ApiException.class,
        () -> service.write(bodyWith("action", "Order:Settle"), null)).getStatus());
    assertEquals(400, assertThrows(ApiException.class,
        () -> service.write(bodyWith("resourceName", "x".repeat(256)), null)).getStatus());
    assertEquals(400, assertThrows(ApiException.class,
        () -> service.write(bodyWith("occurredAt", "2026/09/17 10:00"), null)).getStatus());
    assertEquals(0, repository.records.size(), "校验失败不得落库");
  }

  @Test
  void rejectsEmptyBodyAndOversizedBatch() {
    assertEquals(400, assertThrows(ApiException.class,
        () -> service.write(new HashMap<>(), null)).getStatus());
    assertEquals(400, assertThrows(ApiException.class,
        () -> service.writeBatch(List.of(), null)).getStatus());

    List<Map<String, Object>> tooMany = new ArrayList<>();
    for (int index = 0; index <= AuditLogApplicationService.MAX_BATCH_SIZE; index++) {
      tooMany.add(writeBody("order.item.add"));
    }
    ApiException error = assertThrows(ApiException.class, () -> service.writeBatch(tooMany, null));
    assertEquals(400, error.getStatus());
    assertEquals("BATCH_TOO_LARGE", error.getCode());
  }

  /** 批量逐条幂等：部分命中幂等键时其余条目照常落库，且返回哪一条被跳过。 */
  @Test
  void batchIsIdempotentPerRecord() {
    Map<String, Object> duplicated = writeBody("order.item.add");
    duplicated.put("idempotencyKey", "batch:1");
    service.write(duplicated, null);

    Map<String, Object> fresh = writeBody("order.item.add");
    fresh.put("idempotencyKey", "batch:2");

    AuditBatchWriteResult result = service.writeBatch(List.of(duplicated, fresh), "platform-order-service");

    assertEquals(2, result.items().size());
    assertEquals(1, result.duplicated());
    assertEquals(1, result.accepted());
    assertEquals(2, repository.records.size());
  }

  /** 批量里有一条非法时整批 400，并在消息里指出下标（不做一半写入一半失败的模糊结果）。 */
  @Test
  void batchValidationFailureReportsIndex() {
    Map<String, Object> invalid = writeBody("order.item.add");
    invalid.put("result", "FAILED");

    ApiException error = assertThrows(ApiException.class,
        () -> service.writeBatch(List.of(writeBody("order.item.add"), invalid), null));

    assertEquals(400, error.getStatus());
    assertTrue(error.getMessage().contains("records[1]"));
    assertEquals(0, repository.records.size());
  }

  private Map<String, Object> writeBody(String action) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenantId", 1001);
    body.put("operatorId", 77);
    body.put("action", action);
    body.put("resourceType", "ord_order_item");
    body.put("resourceId", "5");
    return body;
  }

  private Map<String, Object> bodyWith(String key, Object value) {
    Map<String, Object> body = writeBody("order.item.add");
    body.put(key, value);
    return body;
  }

  /** 内存仓储：只实现本测试用到的语义（唯一键幂等 + 顺序保存）。 */
  private static final class InMemoryAuditLogRepository implements AuditLogRepository {
    private final List<AuditLog> records = new ArrayList<>();
    private long sequence = 0;

    @Override
    public SaveResult saveIfAbsent(AuditLog log) {
      Optional<AuditLog> existing = records.stream()
          .filter(item -> item.getTenantId().equals(log.getTenantId())
              && item.getIdempotencyKey().equals(log.getIdempotencyKey()))
          .findFirst();
      if (existing.isPresent()) {
        return new SaveResult(existing.get().getId(), true);
      }
      log.setId(++sequence);
      records.add(log);
      return new SaveResult(log.getId(), false);
    }

    @Override
    public Optional<AuditLog> findById(long id) {
      return records.stream().filter(item -> item.getId() == id).findFirst();
    }

    @Override
    public List<AuditLog> findPage(Query query) {
      return List.copyOf(records);
    }

    @Override
    public long count(Query query) {
      return records.size();
    }

    @Override
    public Map<Long, String> tenantNames(Collection<Long> tenantIds) {
      return Map.of(1001L, "A380 测试租户");
    }

    @Override
    public Map<Long, OperatorDisplay> operatorDisplays(Collection<Long> operatorIds) {
      return Map.of();
    }
  }

  @Test
  void occurredAtAcceptsIsoAndSpaceFormats() {
    Map<String, Object> body = writeBody("order.item.add");
    body.put("occurredAt", "2026-09-17 10:00:00");
    service.write(body, null);
    assertEquals(LocalDateTime.of(2026, 9, 17, 10, 0), repository.records.get(0).getOccurredAt());
  }

  /**
   * ① 回归：上报体**不带** occurredAt 时必须落 created_at（同一时刻），绝不能写 NULL。
   *
   * <p>现场证据：`SELECT id, action, occurred_at, created_at FROM iam_audit_log` 新写入行 occurred_at
   * 全为 NULL，按发生时间的筛选/排序/时间范围过滤会整段漏行。
   */
  @Test
  void occurredAtFallsBackToCreatedAtWhenReportBodyOmitsIt() {
    service.write(writeBody("order.item.add"), null);

    AuditLog saved = repository.records.get(0);
    assertNotNull(saved.getOccurredAt(), "occurred_at 不能为空：空值会让按发生时间的查询静默丢行");
    assertEquals(saved.getCreatedAt(), saved.getOccurredAt());
  }

  /** 上报体**提供** occurredAt 时以其为准（补录/延迟上报的业务时间允许早于落库时间）。 */
  @Test
  void explicitOccurredAtWinsOverCreatedAt() {
    LocalDateTime occurredAt = LocalDateTime.of(2026, 9, 1, 8, 30);
    Map<String, Object> body = writeBody("order.item.add");
    body.put("occurredAt", occurredAt.toString());

    service.write(body, null);

    AuditLog saved = repository.records.get(0);
    assertEquals(occurredAt, saved.getOccurredAt());
    assertNotEquals(saved.getCreatedAt(), saved.getOccurredAt());
  }
}
