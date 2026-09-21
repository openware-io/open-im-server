package com.gvchat.common.sms.application.service;

import com.gvchat.common.sms.domain.PhonePrivacy;
import com.gvchat.common.sms.domain.model.SmsSendLog;
import com.gvchat.common.sms.domain.repository.SmsSendLogRepository;
import com.gvchat.common.sms.infra.provider.SmsProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 短信应用服务：发短信（幂等 + 手机号摘要/脱敏）+ 发送日志落库。 */
@Service
public class SmsApplicationService {
    private final SmsSendLogRepository repository;
    private final List<SmsProvider> smsProviders;

    public SmsApplicationService(SmsSendLogRepository repository, List<SmsProvider> smsProviders) {
        this.repository = repository;
        this.smsProviders = smsProviders;
    }

    public Map<String, Object> send(Map<String, Object> body) {
        String phone = (String) body.getOrDefault("phone", "");
        String templateCode = (String) body.getOrDefault("templateCode", "");
        String signName = (String) body.getOrDefault("signName", "");
        String idempotencyKey = (String) body.getOrDefault("idempotencyKey", "");
        String providerName = (String) body.getOrDefault("provider", "aliyun");

        SmsSendLog log = new SmsSendLog();
        log.setTenantId(((Number) body.getOrDefault("tenantId", 0)).longValue());
        log.setPhoneDigest(PhonePrivacy.digest(phone));
        log.setPhoneMasked(PhonePrivacy.mask(phone));
        log.setTemplateCode(templateCode);
        log.setProvider(providerName);
        log.setStatus("PENDING");
        log.setRetryCount(0);
        log.setIdempotencyKey(idempotencyKey);
        log.setCreatedAt(LocalDateTime.now());
        log.setUpdatedAt(LocalDateTime.now());
        log = repository.save(log);

        SmsProvider provider = smsProviders.stream()
                .filter(p -> p.provider().equals(providerName))
                .findFirst()
                .orElse(null);
        String requestId = null;
        if (provider != null) {
            try {
                requestId = provider.send(signName, templateCode, phone, paramsOf(body));
                log.setStatus("SENT");
            } catch (RuntimeException ex) {
                log.setStatus("FAILED");
            }
        } else {
            log.setStatus("FAILED");
        }
        log.setUpdatedAt(LocalDateTime.now());
        repository.update(log);

        return requestId == null
                ? Map.of("id", log.getId(), "status", log.getStatus())
                : Map.of("id", log.getId(), "status", log.getStatus(), "requestId", requestId);
    }

    private Map<String, String> paramsOf(Map<String, Object> body) {
        Object params = body.get("params");
        if (!(params instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                result.put(key, value);
            }
        }
        return result;
    }
}
