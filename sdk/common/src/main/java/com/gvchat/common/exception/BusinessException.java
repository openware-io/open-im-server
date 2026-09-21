package com.gvchat.common.exception;

import lombok.Getter;

/**
 * 业务逻辑异常，表示可预期的领域规则违反。
 * <p>
 * 通常由应用层捕获并映射为 {@link ApiException} 或统一错误响应。
 * </p>
 */
@Getter
public class BusinessException extends RuntimeException {
  private final String code;

  /**
   * 构造仅含消息的业务异常。
   *
   * @param message 错误描述信息
   */
  public BusinessException(String message) {
    this(null, message);
  }

  /**
   * 构造含业务错误码与消息的业务异常。
   *
   * @param code  业务错误码，可为 {@code null}
   * @param message 错误描述信息
   */
  public BusinessException(String code, String message) {
    super(message);
    this.code = code;
  }
}
