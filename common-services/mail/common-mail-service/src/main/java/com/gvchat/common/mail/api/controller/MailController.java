package com.gvchat.common.mail.api.controller;

import com.gvchat.common.mail.application.service.MailApplicationService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 邮件：发邮件（幂等 + 收件人摘要）+ 配置查询占位。controller 路径不带 /api。 */
@RestController
public class MailController {
    private final MailApplicationService service;

    public MailController(MailApplicationService service) { this.service = service; }

    @PostMapping("/internal/mail/send")
    public Map<String, Object> send(@RequestBody Map<String, Object> body) {
        return service.send(body);
    }

    @GetMapping("/admin/mail/config")
    public Object config() { return Map.of("providers", new String[]{"smtp"}); }
}
