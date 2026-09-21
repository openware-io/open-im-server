package com.gvchat.group.idaas.handler;

import com.gvchat.common.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * 将 ApiException / 参数校验异常 / 框架异常统一转换为稳定的 JSON 错误结构（{code, message}）。
 *
 * <p>此前只映射 ApiException 与 MethodArgumentNotValidException：任何非业务异常都退化成 Spring
 * 默认错误体，in-context 的未知路径也拿不到统一 JSON。注意本服务配置了
 * {@code server.servlet.context-path: /idaas}，落在 context path 之外的 URL（如 {@code GET /zzz}）
 * 由容器在进入 Spring MVC 之前直接应答，应用层无法拦截；in-context 的 404/405/415 现已由本类统一处理。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
    Map<String, Object> body = new LinkedHashMap<>();
    if (ex.getCode() != null) {
      body.put("code", ex.getCode());
    }
    body.put("message", ex.getMessage());
    return ResponseEntity.status(ex.getStatus()).body(body);
  }

  /** 参数校验失败：只回传校验文案，不再拼接英文字段技术名。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
    String message = ex.getBindingResult().getFieldErrors().stream()
        .findFirst().map(e -> e.getDefaultMessage()).orElse("请求参数不合法");
    return badRequest("INVALID_ARGUMENT", message);
  }

  /** 缺少必填请求头：必须收敛成统一 {code,message}，不能被兜底吞成 500。 */
  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex) {
    return badRequest("REQUEST_HEADER_MISSING", "缺少请求头: " + ex.getHeaderName());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
    return badRequest("REQUEST_PARAM_MISSING", "缺少请求参数: " + ex.getParameterName());
  }

  /** 请求体格式错误属于客户端错误，必须回 400 而不是 500。 */
  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> handleUnreadable(
      org.springframework.http.converter.HttpMessageNotReadableException ex) {
    return badRequest("REQUEST_BODY_INVALID", "请求体格式错误");
  }

  /** 未知路径：必须回 404，不能被 Exception 兜底吞成 500。 */
  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body("ROUTE_NOT_FOUND", "接口不存在"));
  }

  /** 请求方法不被支持：405。 */
  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
    return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body("METHOD_NOT_ALLOWED", "请求方法不被支持"));
  }

  /** 请求 Content-Type 不被支持：415。 */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
    return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        .body(body("MEDIA_TYPE_UNSUPPORTED", "请求内容类型不被支持"));
  }

  /** 兜底：必须记录堆栈与请求路径，否则线上 500 无法定位。 */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex, HttpServletRequest request) {
    log.error("Unhandled group-idaas-service exception, uri={}",
        request == null ? null : request.getRequestURI(), ex);
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
