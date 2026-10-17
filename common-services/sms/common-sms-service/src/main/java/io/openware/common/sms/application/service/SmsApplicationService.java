package io.openware.common.sms.application.service;

import io.openware.common.sms.domain.PhonePrivacy;
import io.openware.common.sms.domain.model.SmsSendLog;
import io.openware.common.sms.domain.repository.SmsSendLogRepository;
import io.openware.common.sms.infra.provider.SmsProvider;
import org.springframework.stereotype.Service;

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

        SmsSendLog log = SmsSendLog.pending(((Number) body.getOrDefault("tenantId", 0)).longValue(),
                PhonePrivacy.digest(phone), PhonePrivacy.mask(phone), templateCode, providerName, idempotencyKey);
        log = repository.save(log);

        SmsProvider provider = smsProviders.stream()
                .filter(p -> p.provider().equals(providerName))
                .findFirst()
                .orElse(null);
        String requestId = null;
        if (provider != null) {
            try {
                requestId = provider.send(signName, templateCode, phone, paramsOf(body));
                // 状态迁移收在领域模型上（markSent/markFailed），应用层只决定走哪条分支。
                log = log.markSent(requestId, null);
            } catch (RuntimeException ex) {
                log = log.markFailed(null);
            }
        } else {
            log = log.markFailed(null);
        }
        repository.update(log);

        return requestId == null
                ? Map.of("id", log.id(), "status", log.status())
                : Map.of("id", log.id(), "status", log.status(), "requestId", requestId);
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
