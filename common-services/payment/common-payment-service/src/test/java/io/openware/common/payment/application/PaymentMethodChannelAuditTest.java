package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.payment.infra.persistence.mapper.ChannelConfigMapper;
import io.openware.common.payment.infra.persistence.mapper.TenantPaymentMethodMapper;
import io.openware.common.payment.infra.persistence.po.TenantPaymentMethodPo;
import io.openware.infrastructure.audit.AuditClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 支付方式授权/用户开关（{@code PaymentMethodApplicationService}）与支付渠道开通
 * （{@code ChannelApplicationService}）的成功与失败留痕：这两处此前连成功留痕都没有。
 *
 * <p>无状态前置校验（未知支付方式 / 非线上渠道）不写失败痕迹——只覆盖写操作的执行结果。
 */
class PaymentMethodChannelAuditTest {

  private static final long TENANT_ID = 1001L;
  private static final Long STORE_ID = 2001L;

  private TenantPaymentMethodMapper tenantPaymentMethodMapper;
  private ChannelConfigMapper channelConfigMapper;
  private AuditClient auditClient;
  private PaymentMethodApplicationService methodService;
  private ChannelApplicationService channelService;

  @BeforeEach
  void setUp() {
    tenantPaymentMethodMapper = mock(TenantPaymentMethodMapper.class);
    channelConfigMapper = mock(ChannelConfigMapper.class);
    auditClient = mock(AuditClient.class);
    channelService = new ChannelApplicationService(channelConfigMapper, auditClient);
    methodService = new PaymentMethodApplicationService(tenantPaymentMethodMapper, channelService, auditClient);
  }

  @Test
  void setGrant_writesSucceededAudit() {
    methodService.setGrant(TENANT_ID, "WALLET", true);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment-method.grant", record.action());
    assertEquals("支付方式授权", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    assertEquals("tenant_payment_method", record.resourceType());
    assertEquals("WALLET", record.resourceName());
  }

  @Test
  void setGrant_writesFailureAuditWithExceptionClassName() {
    when(tenantPaymentMethodMapper.selectOne(any())).thenReturn(method("WALLET"));
    when(tenantPaymentMethodMapper.updateById(any(TenantPaymentMethodPo.class)))
        .thenThrow(new IllegalStateException("db down"));

    assertThrows(IllegalStateException.class, () -> methodService.setGrant(TENANT_ID, "WALLET", true));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment-method.grant", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IllegalStateException", record.errorCode());
    assertNull(record.idempotencyKey());
    // 失败详情只留方式码，不含商户参数/金额。
    assertEquals("{\"method\":\"WALLET\"}", record.detailJson());
  }

  @Test
  void setUserEnabled_writesSucceededAudit() {
    methodService.setUserEnabled(TENANT_ID, "POINT", false);

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment-method.user_enabled", record.action());
    assertEquals("支付方式用户开关", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
  }

  @Test
  void setUserEnabled_writesFailureAudit() {
    when(tenantPaymentMethodMapper.selectOne(any())).thenReturn(method("POINT"));
    when(tenantPaymentMethodMapper.updateById(any(TenantPaymentMethodPo.class)))
        .thenThrow(new IllegalStateException("db down"));

    assertThrows(IllegalStateException.class, () -> methodService.setUserEnabled(TENANT_ID, "POINT", false));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment-method.user_enabled", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IllegalStateException", record.errorCode());
  }

  /** 未知支付方式是无状态前置校验：不写失败痕迹。 */
  @Test
  void setGrant_unknownMethodIsNotAudited() {
    ApiException ex = assertThrows(ApiException.class, () -> methodService.setGrant(TENANT_ID, "BITCOIN", true));

    assertEquals("PAYMENT_METHOD_UNKNOWN", ex.getCode());
    verifyNoInteractions(auditClient);
  }

  @Test
  void setEnabled_writesSucceededAudit() {
    channelService.setEnabled(TENANT_ID, STORE_ID, "ALIPAY", true, "merchant-001");

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment-channel.toggle", record.action());
    assertEquals("支付渠道开通/关闭", record.actionLabel());
    assertEquals(AuditClient.AuditRecord.RESULT_SUCCESS, record.result());
    assertEquals("pay_channel_config", record.resourceType());
    // 详情不含 merchantId（商户参数不入审计）。
    assertEquals(false, record.detailJson().contains("merchant-001"), record.detailJson());
  }

  @Test
  void setEnabled_writesFailureAudit() {
    when(channelConfigMapper.selectOne(any())).thenThrow(new IllegalStateException("db down"));

    assertThrows(IllegalStateException.class,
        () -> channelService.setEnabled(TENANT_ID, STORE_ID, "ALIPAY", true, "merchant-001"));

    AuditClient.AuditRecord record = capturedAudit();
    assertEquals("payment-channel.toggle", record.action());
    assertEquals(AuditClient.AuditRecord.RESULT_FAILURE, record.result());
    assertEquals("IllegalStateException", record.errorCode());
    assertNull(record.idempotencyKey());
    assertEquals(false, record.detailJson().contains("merchant-001"), record.detailJson());
  }

  /** 非线上渠道是无状态前置校验：不写失败痕迹。 */
  @Test
  void setEnabled_nonOnlineChannelIsNotAudited() {
    assertThrows(IllegalStateException.class,
        () -> channelService.setEnabled(TENANT_ID, STORE_ID, "CASH", true, null));

    verifyNoInteractions(auditClient);
  }

  private static TenantPaymentMethodPo method(String method) {
    TenantPaymentMethodPo po = new TenantPaymentMethodPo();
    po.setId(9L);
    po.setTenantId(TENANT_ID);
    po.setMethod(method);
    po.setGranted(0);
    po.setUserEnabled(1);
    po.setStatus("ACTIVE");
    return po;
  }

  private AuditClient.AuditRecord capturedAudit() {
    ArgumentCaptor<AuditClient.AuditRecord> captor = ArgumentCaptor.forClass(AuditClient.AuditRecord.class);
    verify(auditClient).recordAsync(captor.capture());
    return captor.getValue();
  }
}
