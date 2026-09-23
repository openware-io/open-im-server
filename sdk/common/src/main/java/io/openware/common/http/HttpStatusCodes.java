package io.openware.common.http;

public final class HttpStatusCodes {
  public static final int BAD_REQUEST = 400;
  public static final int UNAUTHORIZED = 401;
  public static final int FORBIDDEN = 403;
  public static final int NOT_FOUND = 404;
  public static final int CONFLICT = 409;
  public static final int GONE = 410;
  public static final int INTERNAL_SERVER_ERROR = 500;
  public static final int NOT_IMPLEMENTED = 501;
  public static final int SERVICE_UNAVAILABLE = 503;
  /** 网关限流（RateLimitFilter 的 code=RATE_LIMITED）对应的 HTTP 状态。 */
  public static final int TOO_MANY_REQUESTS = 429;
  /** 与 org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT 对齐（状态/业务语义冲突）。 */
  public static final int UNPROCESSABLE_CONTENT = 422;

  private HttpStatusCodes() {
  }
}

