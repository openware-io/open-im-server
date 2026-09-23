package io.openware.common.sms.api.controller;

import io.openware.common.sms.application.service.SmsApplicationService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 短信：发短信（幂等 + 手机号脱敏摘要）+ 配置查询占位。controller 路径不带 /api。 */
@RestController
public class SmsController {
    private final SmsApplicationService service;

    public SmsController(SmsApplicationService service) { this.service = service; }

    @PostMapping("/internal/sms/send")
    public Map<String, Object> send(@RequestBody Map<String, Object> body) {
        return service.send(body);
    }

    @GetMapping("/admin/sms/config")
    public Object config() { return Map.of("providers", new String[]{"aliyun", "tencent"}); }
}
