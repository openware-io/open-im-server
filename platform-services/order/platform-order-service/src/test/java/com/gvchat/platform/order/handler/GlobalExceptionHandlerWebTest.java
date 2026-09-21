package com.gvchat.platform.order.handler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gvchat.common.exception.BusinessException;
import com.gvchat.platform.order.application.DailySerialNumberGenerator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F12：写接口缺少必填请求头（如 Idempotency-Key）时，必须返回本模块统一的 {code,message} 错误体，
 * 而不是 Spring 默认错误体（timestamp/path/error），更不能落到兜底 500。
 * 用测试专用 controller 复现「必填头缺失」这一绑定失败场景。
 */
class GlobalExceptionHandlerWebTest {

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new RequiredHeaderProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void missingRequiredHeaderReturnsUnifiedErrorBody() throws Exception {
        mvc.perform(post("/probe/requires-header")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_HEADER_MISSING"))
                .andExpect(jsonPath("$.message").value("缺少请求头: Idempotency-Key"))
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    @Test
    void presentHeaderIsAccepted() throws Exception {
        mvc.perform(post("/probe/requires-header")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "idem-1")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotencyKey").value("idem-1"));
    }

    @Test
    void missingRequiredParamReturnsUnifiedErrorBody() throws Exception {
        mvc.perform(post("/probe/requires-param")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_PARAM_MISSING"))
                .andExpect(jsonPath("$.message").value("缺少请求参数: storeId"));
    }

    /**
     * 单号序号服务不可用 → 503（失败关闭）：不是调用方的参数问题（不能回 400），
     * 也不能被兜底吞成 500，前端才能按「稍后重试」处理。
     */
    @Test
    void docNoSequenceUnavailableReturns503() throws Exception {
        mvc.perform(post("/probe/sequence-unavailable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(DailySerialNumberGenerator.CODE_SEQUENCE_UNAVAILABLE));
    }

    /** 测试专用端点：必填请求头 / 必填查询参数。 */
    @RestController
    static class RequiredHeaderProbeController {

        @PostMapping("/probe/requires-header")
        public Map<String, String> requiresHeader(@RequestHeader("Idempotency-Key") String idempotencyKey) {
            return Map.of("idempotencyKey", idempotencyKey);
        }

        @PostMapping("/probe/requires-param")
        public Map<String, String> requiresParam(@RequestParam("storeId") String storeId) {
            return Map.of("storeId", storeId);
        }

        /** 复现「单号序号服务不可用」：验证错误码到 HTTP 503 的映射。 */
        @PostMapping("/probe/sequence-unavailable")
        public Map<String, String> sequenceUnavailable() {
            throw new BusinessException(DailySerialNumberGenerator.CODE_SEQUENCE_UNAVAILABLE,
                    "单号生成失败：序号服务不可用，请稍后重试");
        }
    }
}
