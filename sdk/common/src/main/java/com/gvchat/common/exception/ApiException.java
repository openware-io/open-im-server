package com.gvchat.common.exception;

/**
 * API 层统一异常，携HTTP 状态码与可选业务错误码。
 * <p>
 * 由全局异常处理器捕获并转换为标HTTP 响应。
 * </p>
 */
public class ApiException extends RuntimeException {
  private final int status;
  private final String code;

  /**
   * 构造仅HTTP 状态与消息API 异常。
   *
   * @param status HTTP 响应状态码
   * @param message 错误描述信息
   */
  public ApiException(int status, String message) {
    this(status, null, message);
  }

  /**
   * 构造含 HTTP 状态、业务错误码与消息的 API 异常。
   *
   * @param status HTTP 响应状态码
   * @param code  业务错误码，可为 {@code null}
   * @param message 错误描述信息
   */
  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int getStatus() {
    return status;
  }

  public String getCode() {
    return code;
  }
}
