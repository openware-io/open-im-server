package io.openware.infrastructure.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

/** 失败留痕错误码口径：业务稳定码优先、异常类名兜底、超长截断到列宽。 */
class AuditErrorCodesTest {

  @Test
  void prefersStableBusinessCodes() {
    assertEquals("LEDGER_INSUFFICIENT",
        AuditErrorCodes.of(new ApiException(422, "LEDGER_INSUFFICIENT", "储值余额不足")));
    assertEquals("ROOM_TYPE_IN_USE",
        AuditErrorCodes.of(new BusinessException("ROOM_TYPE_IN_USE", "房型仍被包厢使用")));
  }

  @Test
  void fallsBackToExceptionClassThenUnknown() {
    assertEquals("IllegalStateException", AuditErrorCodes.of(new IllegalStateException("复核请求不存在")));
    // 业务异常没带稳定码时退化为异常类名，绝不产生空码。
    assertEquals("ApiException", AuditErrorCodes.of(new ApiException(500, null, "无错误码")));
    assertEquals("ApiException", AuditErrorCodes.of(new ApiException(500, "  ", "空错误码")));
    assertEquals(AuditErrorCodes.UNKNOWN, AuditErrorCodes.of((Throwable) null));
  }

  /** error_code 列宽 varchar(64)：超长必须截断，否则整条审计上报会被判 400（等于丢掉失败留痕）。 */
  @Test
  void truncatesToColumnWidth() {
    String oversized = "X".repeat(100);
    String code = AuditErrorCodes.of(new BusinessException(oversized, "超长错误码"));

    assertEquals(AuditErrorCodes.MAX_LENGTH, code.length());
    assertEquals(oversized.substring(0, AuditErrorCodes.MAX_LENGTH), code);
  }

  @Test
  void codeTextIsNormalizedAndNeverBlank() {
    assertEquals("HTTP_500", AuditErrorCodes.ofCode("  HTTP_500 "));
    assertEquals(AuditErrorCodes.UNKNOWN, AuditErrorCodes.ofCode(null));
    assertEquals(AuditErrorCodes.UNKNOWN, AuditErrorCodes.ofCode("   "));
  }
}
