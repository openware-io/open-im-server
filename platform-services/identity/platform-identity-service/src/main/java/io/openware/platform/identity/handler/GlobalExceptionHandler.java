package io.openware.platform.identity.handler;

import io.openware.common.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证/会话服务统一异常出口：业务异常 + Spring MVC 框架 4xx 异常 + 500 兜底，全部返回 {code, message}。
 *
 * <p>此前本 advice 只映射 {@link ApiException} 与 {@link IllegalArgumentException}：登录失败
 * （{@code IllegalStateException}）、未知路径、方法不支持等全部落到 Spring 默认错误体，
 * 前端拿不到 401/404/405 也无法按 {@code code} 分支。这里补齐显式映射与兜底堆栈。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("message", ex.getMessage());
        if (ex.getCode() != null) {
            body.put("code", ex.getCode());
        }
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /** 参数不合法：固定中文文案，原始异常只进日志（不回显内部解析信息）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Identity invalid argument", ex);
        return error(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", "请求参数不合法");
    }

    /** 请求体不是合法 JSON / 字段类型不符：客户端错误，必须回 400 而不是 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "REQUEST_BODY_INVALID", "请求体格式错误");
    }

    /** 缺少必填请求参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, "REQUEST_PARAM_MISSING", "缺少请求参数: " + ex.getParameterName());
    }

    /** 缺少必填请求头（如 X-CSRF-Token）：既不能落 500，也不能漏出 Spring 默认错误体。 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        return error(HttpStatus.BAD_REQUEST, "REQUEST_HEADER_MISSING", "缺少请求头: " + ex.getHeaderName());
    }

    /** 路径/查询参数类型不匹配。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return error(HttpStatus.BAD_REQUEST, "REQUEST_PARAM_INVALID", "请求参数类型不正确: " + ex.getName());
    }

    /** 未知路径：必须回 404；被 {@code Exception} 兜底吞掉会误报成 500。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
        return error(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "接口不存在");
    }

    /** 请求方法不被支持：405（此前被兜底吞成 500）。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "请求方法不被支持");
    }

    /** 请求 Content-Type 不被支持：415（此前被兜底吞成 500）。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_UNSUPPORTED", "请求内容类型不被支持");
    }

    /** 兜底：必须记录堆栈，否则线上 500 无日志可查；对外只给中文统一体，不泄露内部细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled identity-service exception, uri={}",
                request == null ? null : request.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误，请稍后重试");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
