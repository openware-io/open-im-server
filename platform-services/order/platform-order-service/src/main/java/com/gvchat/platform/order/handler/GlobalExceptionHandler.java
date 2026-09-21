package com.gvchat.platform.order.handler;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将业务异常映射为稳定 JSON（{code, message}），HTTP 状态按错误码约定（KTV_BUSINESS_01 §12）。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ex.getCode());
        body.put("message", ex.getMessage());
        return ResponseEntity.status(httpStatusFor(ex.getCode())).body(body);
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

    /** 兜底：必须记录堆栈；返回统一 {code,message} 中文体，不再返回英文 "Internal error"。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled order-service exception, uri={}",
                request == null ? null : request.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务内部错误，请稍后重试");
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

    /**
     * 缺少必填请求头（如 Idempotency-Key）：必须收敛成统一 {code,message} 的 400，
     * 而不是让 Spring 的默认错误体（timestamp/path/error）漏出去；更不能落到兜底 500。
     */
    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(
            org.springframework.web.bind.MissingRequestHeaderException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "REQUEST_HEADER_MISSING");
        body.put("message", "缺少请求头: " + ex.getHeaderName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 其它请求绑定错误（cookie/矩阵变量/类型不匹配等）同样按客户端错误回 400，不泄露 500。 */
    @ExceptionHandler(org.springframework.web.bind.ServletRequestBindingException.class)
    public ResponseEntity<Map<String, Object>> handleBinding(
            org.springframework.web.bind.ServletRequestBindingException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "REQUEST_BINDING_INVALID");
        body.put("message", "请求参数绑定失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 路径/查询参数类型不匹配（如 id 传了非数字）。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "REQUEST_PARAM_INVALID");
        body.put("message", "请求参数类型不正确: " + ex.getName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** 未知路径：必须回 404；被 Exception 兜底吞掉会误报成 500。 */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex) {
        return error(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "接口不存在");
    }

    /** 请求方法不被支持：405；被 Exception 兜底吞掉会误报成 500。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "请求方法不被支持");
    }

    /** 请求 Content-Type 不被支持：415；被 Exception 兜底吞掉会误报成 500。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_UNSUPPORTED", "请求内容类型不被支持");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }

    private HttpStatus httpStatusFor(String code) {
        if (code == null) {
            return HttpStatus.BAD_REQUEST;
        }
        return switch (code) {
            case "ORDER_NOT_FOUND", "RESERVATION_NOT_FOUND", "PRODUCT_CATEGORY_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            // 库存不足 = 与当前库存状态冲突（并发抢到最后一件时必现），回 409 让前端提示「已售罄/库存不足」；
            // 明细已回补或无需回补 = 状态不符，回 422。
            case "ORDER_VERSION_CONFLICT", "RESERVATION_VERSION_CONFLICT", "RESOURCE_OCCUPIED", "ORDER_ALREADY_SETTLED", "IDEMPOTENCY_CONFLICT", "INVENTORY_INSUFFICIENT" -> HttpStatus.CONFLICT;
            // 商品分类重名、被商品引用不可删：都是「与当前资源状态冲突」，回 409。
            case "PRODUCT_CATEGORY_DUPLICATED", "PRODUCT_CATEGORY_IN_USE" -> HttpStatus.CONFLICT;
            // 加项审批的状态流转冲突（并发下已被别人确认/拒绝，或已不是 PENDING_APPROVAL）：
            // 文档口径与客户端收敛都要求 409（两端只有 409 才判定为「已被处理」并静默刷新，
            // 见 KTV_RESERVATION_ORDER_STATE_FLOW §11）；落到 default 的 400 会让客户端把
            // 「别人已经处理完了」当成操作失败红色报错。
            case "ORDER_ITEM_STATUS_INVALID" -> HttpStatus.CONFLICT;
            // 同一服务人员被两个服务商品占用：与当前数据状态冲突（不是参数格式问题），回 409。
            case "SERVER_RESOURCE_IN_USE" -> HttpStatus.CONFLICT;
            case "ORDER_STATUS_INVALID", "RESERVATION_STATUS_INVALID", "RESERVATION_SCOPE_INVALID", "KTV_SESSION_PAUSE_DISABLED", "PAYMENT_AMOUNT_MISMATCH", "INVENTORY_RECOVERY_INVALID" -> HttpStatus.UNPROCESSABLE_CONTENT;
            // ORDER_SCOPE_DENIED：C 端消费者只能查看/操作本人订单（他人订单 403，与 PERMISSION_DENIED 同属越权）。
            case "TENANT_SCOPE_DENIED", "STORE_SCOPE_DENIED", "CATALOG_SCOPE_DENIED", "ORDER_SCOPE_DENIED",
                    "PERMISSION_DENIED" -> HttpStatus.FORBIDDEN;
            case "AUTH_CONTEXT_EXPIRED", "SAAS_CONTEXT_REQUIRED", "SAAS_CONTEXT_INVALID",
                    "TENANT_CONTEXT_MISSING", "TENANT_CONTEXT_REQUIRED" -> HttpStatus.UNAUTHORIZED;
            // 资源/房态服务不可达导致的失败关闭：不是调用方的参数问题，回 503 让前端按「稍后重试」处理。
            // DOC_NO_SEQUENCE_UNAVAILABLE 同理：单号序号表不可用时**拒绝创建**（不降级为时间戳），
            // 属于「依赖不可用」而非参数错误，回 503。
            case "RESOURCE_STATE_UNAVAILABLE", "SERVER_RESOURCE_UNAVAILABLE",
                    "DOC_NO_SEQUENCE_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
