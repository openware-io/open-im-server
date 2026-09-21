package com.gvchat.platform.tenant.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.tenant.application.WalletTokenConfigApplicationService;
import com.gvchat.platform.tenant.infra.persistence.mapper.InternalTenantConfigMapper;
import com.gvchat.platform.tenant.infra.persistence.mapper.TenantConfigMapper;
import com.gvchat.platform.tenant.infra.persistence.po.TenantConfigPo;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 租户钱包代币配置（储值品牌名/比例）的写操作留痕回归：
 * 成功路径写 {@code tenant.config.update}；失败出口（缺权限/越权租户/落库失败）同样必须落 FAILURE（③ 遗留）。
 * 读路径（代币品牌名/比例）本身由 {@link WalletTokenConfigApplicationService} 提供，见内部端点用例。
 */
class TenantConfigControllerTest {

  private static final long TENANT_ID = 100L;

  private TenantConfigMapper tenantConfigMapper;
  private InternalTenantConfigMapper internalTenantConfigMapper;
  private AuditClient auditClient;
  private TenantConfigController controller;

  @BeforeEach
  void setUp() {
    tenantConfigMapper = mock(TenantConfigMapper.class);
    internalTenantConfigMapper = mock(InternalTenantConfigMapper.class);
    auditClient = mock(AuditClient.class);
    controller = new TenantConfigController(tenantConfigMapper, auditClient,
        new WalletTokenConfigApplicationService(internalTenantConfigMapper));
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  @Test
  void updateRecordsSuccessAuditWithBrandAndRatio() {
    withContext("tenant.tenant.manage");
    when(tenantConfigMapper.selectList(any())).thenReturn(List.of());

    controller.update(new TenantConfigController.UpdateRequest(null, "A380币", 100));

    AuditClient.AuditRecord record = capturedAudit();
    assertThat(record.action()).isEqualTo("tenant.config.update");
    assertThat(record.tenantId()).isEqualTo(TENANT_ID);
    assertThat(record.detailJson()).contains("\"brandName\":\"A380币\"").contains("\"ratio\":100");
    assertThat(record.result()).isNotEqualTo(AuditClient.AuditRecord.RESULT_FAILURE);
  }

  /** ③ 失败留痕：缺经营权限（403 PERMISSION_DENIED）时也必须留一条 FAILURE，便于发现越权尝试。 */
  @Test
  void updateWithoutPermissionRecordsFailureAudit() {
    withContext();

    assertThatThrownBy(() -> controller.update(new TenantConfigController.UpdateRequest(null, "A380币", 100)))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("PERMISSION_DENIED"));

    AuditClient.AuditRecord record = capturedAudit();
    assertThat(record.action()).isEqualTo("tenant.config.update");
    assertThat(record.result()).isEqualTo(AuditClient.AuditRecord.RESULT_FAILURE);
    assertThat(record.errorCode()).isEqualTo("PERMISSION_DENIED");
    assertThat(record.resourceId()).isEqualTo("100");
  }

  /** ③ 失败留痕：跨租户参数（403 TENANT_SCOPE_DENIED）同样留痕，且不得把配置写到别的租户。 */
  @Test
  void updateForForeignTenantRecordsFailureAudit() {
    withContext("tenant.tenant.manage");

    assertThatThrownBy(() -> controller.update(
        new TenantConfigController.UpdateRequest(200L, "A380币", 100)))
        .isInstanceOf(ApiException.class)
        .satisfies(ex -> assertThat(((ApiException) ex).getCode()).isEqualTo("TENANT_SCOPE_DENIED"));

    AuditClient.AuditRecord record = capturedAudit();
    assertThat(record.result()).isEqualTo(AuditClient.AuditRecord.RESULT_FAILURE);
    assertThat(record.errorCode()).isEqualTo("TENANT_SCOPE_DENIED");
    // 失败记录只能落在请求声明的租户上，便于排查越权；写入本身已被拒绝（无 insert/update）。
    assertThat(record.tenantId()).isEqualTo(200L);
    verify(tenantConfigMapper, org.mockito.Mockito.never()).insert(any(TenantConfigPo.class));
  }

  /** ③ 失败留痕：落库异常（框架级失败）退化为异常类名，绝不能出现空错误码。 */
  @Test
  void updateDatabaseFailureRecordsFailureAuditWithClassName() {
    withContext("tenant.tenant.manage");
    when(tenantConfigMapper.selectList(any())).thenReturn(List.of());
    when(tenantConfigMapper.insert(any(TenantConfigPo.class)))
        .thenThrow(new IllegalStateException("数据库连接不可用"));

    assertThatThrownBy(() -> controller.update(new TenantConfigController.UpdateRequest(null, "A380币", 100)))
        .isInstanceOf(IllegalStateException.class);

    AuditClient.AuditRecord record = capturedAudit();
    assertThat(record.result()).isEqualTo(AuditClient.AuditRecord.RESULT_FAILURE);
    assertThat(record.errorCode()).isEqualTo("IllegalStateException");
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }

  private void withContext(String... permissions) {
    TenantContextHolder.set(new TenantContext(TENANT_ID, null, null, 1L, 1, List.of(permissions)));
  }
}
