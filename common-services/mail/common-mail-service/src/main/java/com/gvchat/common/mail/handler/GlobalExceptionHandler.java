package com.gvchat.common.mail.handler;

import com.gvchat.common.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 邮件服务统一异常出口：把业务异常与框架异常映射为稳定 JSON（{code, message}）。
 *
 * <p>此前本服务没有任何 {@code @RestControllerAdvice}，非业务异常会直接变成 Spring 默认错误体
 * {@code {"timestamp","status","error","path"}}，调用方无法按 code 分支，运维也拿不到日志。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> handleApi(ApiException exception) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (exception.getCode() != null) {
      body.put("code", exception.getCode());
    }
    body.put("message", exception.getMessage());
    return ResponseEntity.status(exception.getStatus()).body(body);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
    FieldError fieldError = exception.getBindingResult().getFieldError();
    return badRequest("INVALID_ARGUMENT",
        fieldError == null ? "请求参数不合法" : fieldError.getDefaultMessage());
  }

  /** 缺少必填请求头：必须收敛成统一 {code,message}，不能被兜底吞成 500。 */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException exception) {
    return badRequest("REQUEST_HEADER_MISSING", "缺少请求头: " + exception.getHeaderName());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException exception) {
    return badRequest("REQUEST_PARAM_MISSING", "缺少请求参数: " + exception.getParameterName());
  }

  /** 请求体格式错误属于客户端错误，必须回 400 而不是 500。 */
  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> handleUnreadable(
      org.springframework.http.converter.HttpMessageNotReadableException exception) {
    return badRequest("REQUEST_BODY_INVALID", "请求体格式错误");
  }

  /** 未知路径：必须回 404，不能被 Exception 兜底吞成 500。 */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<Map<String, Object>> handleNotFound(Exception exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("ROUTE_NOT_FOUND", "接口不存在"));
  }

  /** 请求方法不被支持：405。 */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body("METHOD_NOT_ALLOWED", "请求方法不被支持"));
  }

  /** 请求 Content-Type 不被支持：415。 */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException exception) {
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(body("MEDIA_TYPE_UNSUPPORTED", "请求内容类型不被支持"));
  }

  /** 兜底：必须记录堆栈与请求路径，否则线上 500 无法定位。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleGeneric(Exception exception, HttpServletRequest request) {
    log.error("Unhandled mail-service exception, uri={}",
        request == null ? null : request.getRequestURI(), exception);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(body("INTERNAL_ERROR", "服务内部错误，请稍后重试"));
  }

  private ResponseEntity<Map<String, Object>> badRequest(String code, String message) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body(code, message));
  }

  private static Map<String, Object> body(String code, String message) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("code", code);
    body.put("message", message);
    return body;
  }
}
