package com.gvchat.platform.tenant.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.time.TimeRangeParams;
import com.gvchat.platform.tenant.infra.persistence.mapper.TenantMapper;
import com.gvchat.platform.tenant.infra.persistence.po.TenantPo;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClient;

/**
 * 平台级动作的审计操作人回归（② 遗留）：
 * {@code POST /admin/platform/tenants}（平台账号创建租户）此前落库时 {@code operator_id} 为空——
 * 该路由在网关是 SESSION_ONLY，领域服务拿不到任何运营上下文。现在网关注入平台作用域签名上下文，
 * 本测试用该上下文驱动控制器，并断言**真正上报的报文**里带上了操作人。
 */
class TenantControllerTest {

  private static final String SECRET = "platform-tenant-controller-test-secret-0123456789";
  private static final long PLATFORM_ACCOUNT_ID = 900L;

  private TenantMapper tenantMapper;
  private AuditClient auditClient;
  private TenantController controller;

  @BeforeEach
  void setUp() {
    // 纯单元测试没有 MyBatis 上下文：先注册 TableInfo，列表筛选的 LambdaQueryWrapper 才能解析列名。
    TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), TenantPo.class);
    tenantMapper = mock(TenantMapper.class);
    auditClient = mock(AuditClient.class);
    controller = new TenantController(tenantMapper, auditClient);
    // 网关注入的平台作用域上下文：tenantId=0（无租户约束）+ PLATFORM，subject=平台账号。
    TenantContextHolder.set(new TenantContext(0L, null, null, PLATFORM_ACCOUNT_ID, 0, List.of(),
        TenantContext.SCOPE_PLATFORM));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void createTenantAuditsPlatformOperatorFromSignedContext() {
    when(tenantMapper.selectOne(any())).thenReturn(null);
    when(tenantMapper.insert(any(TenantPo.class))).thenAnswer(invocation -> {
      ((TenantPo) invocation.getArgument(0)).setId(1234L);
      return 1;
    });

    TenantPo created = controller.create(
        new TenantController.CreateTenantRequest("T-1001", "A380 一号店", "zh-CN", "Asia/Shanghai"));

    assertThat(created.getId()).isEqualTo(1234L);
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    AuditClient.AuditRecord record = captor.getValue();
    assertThat(record.action()).isEqualTo("tenant.create");
    assertThat(record.resourceType()).isEqualTo("tnt_tenant");
    assertThat(record.resourceId()).isEqualTo("1234");
    assertThat(record.operatorType()).isEqualTo(AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM);

    // 控制器不显式声明操作人：由 SDK 按当前上下文补全，这里断言最终上报报文里的操作人字段。
    Map<String, Object> body = new AuditClient(RestClient.builder().build(), SECRET).buildBody(record);
    assertThat(body.get("operatorId")).isEqualTo(PLATFORM_ACCOUNT_ID);
    assertThat(body.get("operatorType")).isEqualTo(AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM);
    assertThat(body.get("tenantId")).isEqualTo(1234L);
  }

  /** 幂等：tenantCode 已存在时返回既有租户，不重复落库、也不重复留痕。 */
  @Test
  void createTenantIsIdempotentByTenantCode() {
    TenantPo existing = new TenantPo();
    existing.setId(77L);
    existing.setTenantCode("T-1001");
    when(tenantMapper.selectOne(any())).thenReturn(existing);

    assertThat(controller.create(
        new TenantController.CreateTenantRequest("T-1001", "A380 一号店", "zh-CN", "Asia/Shanghai")).getId())
        .isEqualTo(77L);
    verify(tenantMapper, org.mockito.Mockito.never()).insert(any(TenantPo.class));
    verify(auditClient, org.mockito.Mockito.never()).recordAsync(any());
  }

  /**
   * 列表的**租户创建时间**闭区间：条件落在 {@code created_at} 列上，日期形态两端收口到当天起点 /
   * 当天末尾；不传时间参数时不拼任何时间条件（下拉调用行为不变）。
   */
  @Test
  void listAppliesClosedCreatedAtRangeAndTreatsBlankAsNoFilter() {
    when(tenantMapper.selectList(any())).thenReturn(List.of());

    controller.list("2026-09-01", "2026-09-30");
    assertThat(capturedListSql()).contains("created_at >=").contains("created_at <=")
        .doesNotContain("DATE(");
    assertThat(capturedListParams())
        .contains(java.time.LocalDateTime.of(2026, 9, 1, 0, 0))
        .contains(java.time.LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000));

    controller.list(null, null);
    assertThat(capturedListSql()).doesNotContain("created_at >=").doesNotContain("created_at <=");
  }

  /** from > to：400 {@code TIME_RANGE_INVALID}（与全仓其它列表同一错误码），且不查库。 */
  @Test
  void listRejectsInvertedRangeWith400() {
    ApiException failure = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
        () -> controller.list("2026-09-30", "2026-09-01"));

    assertThat(failure.getStatus()).isEqualTo(400);
    assertThat(failure.getCode()).isEqualTo(TimeRangeParams.CODE_TIME_RANGE_INVALID);
    assertThat(failure.getMessage()).isEqualTo(TimeRangeParams.MESSAGE_TIME_RANGE_INVALID);
    verify(tenantMapper, org.mockito.Mockito.never()).selectList(any());
  }

  private String capturedListSql() {
    return capturedListWrapper().getSqlSegment();
  }

  private java.util.Collection<Object> capturedListParams() {
    return capturedListWrapper().getParamNameValuePairs().values();
  }

  @SuppressWarnings("unchecked")
  private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantPo> capturedListWrapper() {
    ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TenantPo>> captor =
        ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
    verify(tenantMapper, org.mockito.Mockito.atLeastOnce()).selectList(captor.capture());
    return captor.getValue();
  }

  /**
   * ③ 失败留痕：平台账号创建租户失败（落库异常）必须落 FAILURE，且仍带平台操作人 ——
   * 失败与成功一样要能回答「谁在什么时候试过创建这个租户」。
   */
  @Test
  void createTenantFailureAuditsPlatformOperatorAndErrorCode() {
    when(tenantMapper.selectOne(any())).thenReturn(null);
    when(tenantMapper.insert(any(TenantPo.class)))
        .thenThrow(new org.springframework.dao.DuplicateKeyException("tenant_code 唯一键冲突"));

    org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DuplicateKeyException.class,
        () -> controller.create(new TenantController.CreateTenantRequest("T-1001", "A380 一号店", "zh-CN",
            "Asia/Shanghai")));

    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    AuditClient.AuditRecord record = captor.getValue();
    assertThat(record.result()).isEqualTo(AuditClient.AuditRecord.RESULT_FAILURE);
    assertThat(record.errorCode()).isEqualTo("DuplicateKeyException");
    assertThat(record.operatorType()).isEqualTo(AuditClient.AuditRecord.OPERATOR_TYPE_PLATFORM);

    Map<String, Object> body = new AuditClient(RestClient.builder().build(), SECRET).buildBody(record);
    assertThat(body.get("operatorId")).isEqualTo(PLATFORM_ACCOUNT_ID);
  }
}
