package com.gvchat.infrastructure.audit;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;

/**
 * 失败审计的 {@code error_code} 口径：与 {@code iam_audit_log.error_code}（varchar(64)）对齐的唯一出处。
 *
 * <p>取码优先级（审计要能按稳定码检索，绝不能出现空码或超长导致上报 400）：
 * <ol>
 *   <li>{@link ApiException} 的稳定业务码（如 {@code LEDGER_INSUFFICIENT}）；</li>
 *   <li>{@link BusinessException} 的业务码（领域服务内部抛出的规则违反）；</li>
 *   <li>异常类名（框架级失败/未归类异常）；</li>
 *   <li>{@code null} 异常（如按响应码判定的失败）→ {@code UNKNOWN_FAILURE}。</li>
 * </ol>
 *
 * <p>超过列宽一律截断：审计上报宁可少几个字符，也不能因为 errorCode 超长被服务端判 400 而丢掉整条失败留痕。
 */
public final class AuditErrorCodes {

  /** 与 {@code iam_audit_log.error_code} 的 varchar(64) 一致。 */
  public static final int MAX_LENGTH = 64;

  /** 无法归类时的兜底码。 */
  public static final String UNKNOWN = "UNKNOWN_FAILURE";

  private AuditErrorCodes() { }

  /** 从失败异常映射出可检索的错误码；{@code failure} 为 {@code null} 时返回 {@link #UNKNOWN}。 */
  public static String of(Throwable failure) {
    return truncate(code(failure));
  }

  /** 直接使用已知错误码（如 BFF 按 HTTP 状态判定失败）时同样做长度归一。 */
  public static String ofCode(String code) {
    return truncate(code == null || code.isBlank() ? UNKNOWN : code.trim());
  }

  private static String code(Throwable failure) {
    if (failure == null) {
      return UNKNOWN;
    }
    if (failure instanceof ApiException apiException) {
      String code = apiException.getCode();
      if (code != null && !code.isBlank()) {
        return code.trim();
      }
    }
    if (failure instanceof BusinessException businessException) {
      String code = businessException.getCode();
      if (code != null && !code.isBlank()) {
        return code.trim();
      }
    }
    String simpleName = failure.getClass().getSimpleName();
    return simpleName == null || simpleName.isBlank() ? UNKNOWN : simpleName;
  }

  private static String truncate(String value) {
    return value.length() <= MAX_LENGTH ? value : value.substring(0, MAX_LENGTH);
  }
}
