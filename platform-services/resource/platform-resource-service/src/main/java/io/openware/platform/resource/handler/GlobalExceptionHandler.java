package io.openware.platform.resource.handler;

import io.openware.common.exception.ApiException;
import io.openware.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资源服务统一异常出口：把业务/权限/上下文异常映射为稳定 JSON（{code, message}）。
 *
 * <p>此前本服务没有 @RestControllerAdvice，controller 抛出的 ApiException（如
 * 401 SAAS_CONTEXT_REQUIRED、403 PERMISSION_DENIED）会退化成框架默认 500
 * `{"status":500,"error":"Internal Server Error"}`，前端只能拿到无法处理的错误页。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (ex.getCode() != null) {
            body.put("code", ex.getCode());
        }
        body.put("message", ex.getMessage());
        HttpStatus status = HttpStatus.resolve(ex.getStatus());
        return ResponseEntity.status(status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status).body(body);
    }

    /**
     * 数据库访问异常：租户上下文缺失时 MyBatis 租户拦截器会抛 IllegalStateException，
     * 这属于鉴权上下文问题（401），不能当成 500 抛给调用方。
     */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException ex) {
        if (causedByTenantContextMissing(ex)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "SAAS_CONTEXT_REQUIRED");
            body.put("message", "缺少有效的租户/门店上下文");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
        log.error("Unhandled resource-service data access exception", ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "DATABASE_ERROR");
        body.put("message", "数据库操作失败，请稍后重试");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** 请求体/参数格式错误属于客户端错误，必须回 400 而不是 500。 */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "REQUEST_BODY_INVALID");
        body.put("message", "请求体格式错误");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(
            org.springframework.web.bind.MissingServletRequestParameterException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "REQUEST_PARAM_MISSING");
        body.put("message", "缺少请求参数: " + ex.getParameterName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 缺少必填请求头：必须收敛成统一 {code,message}，不能被兜底吞成 500。 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "REQUEST_HEADER_MISSING");
        body.put("message", "缺少请求头: " + ex.getHeaderName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 路径/查询参数类型不匹配。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "REQUEST_PARAM_INVALID");
        body.put("message", "请求参数类型不正确: " + ex.getName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 未知路径：必须回 404，不能被 Exception 兜底吞成 500。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "ROUTE_NOT_FOUND");
        body.put("message", "接口不存在");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** 请求方法不被支持：405。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "METHOD_NOT_ALLOWED");
        body.put("message", "请求方法不被支持");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    /** 请求 Content-Type 不被支持：415。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "MEDIA_TYPE_UNSUPPORTED");
        body.put("message", "请求内容类型不被支持");
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(body);
    }

    /** 兜底：必须记录堆栈与请求路径，否则 500 只能看到 "Internal error" 无法定位。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled resource-service exception, uri={}",
                request == null ? null : request.getRequestURI(), ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "INTERNAL_ERROR");
        body.put("message", "服务内部错误，请稍后重试");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    private static boolean causedByTenantContextMissing(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof IllegalStateException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
