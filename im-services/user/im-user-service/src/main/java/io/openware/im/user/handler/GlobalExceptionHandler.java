package io.openware.im.user.handler;

import io.openware.common.exception.ApiException;
import java.util.HashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 用户服务全局异常处理器 *
 * <p>统一对外返回稳定JSON 错误结构，避免控制器层分散编写异常翻译逻辑 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  /**
   * 处理领域层抛出的业务异常   *
   * @param ex 业务异常
   * @return 含业message/code 的响应体
   */
  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> handleApi(ApiException ex, HttpServletRequest request) {
    Map<String, Object> body = new HashMap<>();
    body.put("message", ex.getMessage());
    if (ex.getCode() != null) {
      body.put("code", ex.getCode());
    }
    if (request.getRequestURI().startsWith("/oauth")) {
      body.put("error", oauthError(ex));
      body.put("error_description", ex.getMessage());
    }
    return ResponseEntity.status(ex.getStatus()).body(body);
  }

  private String oauthError(ApiException ex) {
    if (ex.getCode() != null && ex.getCode().matches("[a-z_]+")) {
      return ex.getCode();
    }
    return switch (ex.getStatus()) {
      case 401 -> "invalid_client";
      case 403 -> "access_denied";
      default -> "invalid_request";
    };
  }

  /**
   * 处理参数校验失败   *
   * <p>当前只回传首个字段错误，目的是让移动端与前端快速给出明确反馈，避免一次响应塞入过多噪声   *
   * @param ex 参数校验异常
   * @return HTTP 400 响应
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
    Map<String, Object> body = new HashMap<>();
    FieldError fe = ex.getBindingResult().getFieldError();
    body.put("message", fe != null ? fe.getDefaultMessage() : "Validation failed");
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  /** 请求体格式错误属于客户端错误，必须回 400 而不是 500。 */
  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> handleUnreadable(
      org.springframework.http.converter.HttpMessageNotReadableException ex) {
    return error(HttpStatus.BAD_REQUEST, "REQUEST_BODY_INVALID", "请求体格式错误");
  }

  /** 缺少必填请求参数：此前被 Exception 兜底吞成 500。 */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
    return error(HttpStatus.BAD_REQUEST, "REQUEST_PARAM_MISSING", "缺少请求参数: " + ex.getParameterName());
  }

  /** 未知路径：必须回 404，不能被 Exception 兜底吞成 500。 */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
    return error(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "接口不存在");
  }

  /** 请求方法不被支持：405。 */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
    return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "请求方法不被支持");
  }

  /** 请求 Content-Type 不被支持：415。 */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
    return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_UNSUPPORTED", "请求内容类型不被支持");
  }

  /**
   * 兜底处理未分类异常   *
   * <p>对外只返回固定中文文案与 INTERNAL_ERROR，原始异常信息仅进日志：此前此处把
   * {@code ex.getMessage()} 原样写进响应体，泄露了 Jackson 解析器细节、Java 方法签名与内部路径   *
   * @param ex 未分类异   * @return HTTP 500 响应
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex, HttpServletRequest request) {
    log.error("Unhandled im-user-service exception, uri={}",
        request == null ? null : request.getRequestURI(), ex);
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误，请稍后重试");
  }

  private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
    Map<String, Object> body = new HashMap<>();
    body.put("code", code);
    body.put("message", message);
    return ResponseEntity.status(status).body(body);
  }
}
