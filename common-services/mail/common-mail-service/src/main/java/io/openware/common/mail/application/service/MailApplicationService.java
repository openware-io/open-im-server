package io.openware.common.mail.application.service;

import io.openware.common.mail.domain.RecipientPrivacy;
import io.openware.common.mail.domain.model.MailSendLog;
import io.openware.common.mail.domain.repository.MailSendLogRepository;
import io.openware.common.mail.infra.provider.MailProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/** 邮件应用服务：发邮件（幂等 + 收件人摘要/脱敏）+ 发送日志落库。 */
@Service
public class MailApplicationService {
    private final MailSendLogRepository repository;
    private final MailProvider mailProvider;

    public MailApplicationService(MailSendLogRepository repository, MailProvider mailProvider) {
        this.repository = repository;
        this.mailProvider = mailProvider;
    }

    public Map<String, Object> send(Map<String, Object> body) {
        String to = (String) body.getOrDefault("to", "");
        String templateCode = (String) body.getOrDefault("templateCode", "");
        String subject = (String) body.getOrDefault("subject", "");
        String content = (String) body.getOrDefault("content", subject);
        String from = (String) body.getOrDefault("from", "");
        String idempotencyKey = (String) body.getOrDefault("idempotencyKey", "");

        MailSendLog log = new MailSendLog();
        log.setTenantId(((Number) body.getOrDefault("tenantId", 0)).longValue());
        log.setRecipientDigest(RecipientPrivacy.digest(to));
        log.setRecipientMasked(RecipientPrivacy.mask(to));
        log.setTemplateCode(templateCode);
        log.setSubject(subject);
        log.setStatus("PENDING");
        log.setRetryCount(0);
        log.setIdempotencyKey(idempotencyKey);
        log.setCreatedAt(LocalDateTime.now());
        log.setUpdatedAt(LocalDateTime.now());
        log = repository.save(log);

        try {
            mailProvider.send(from, to, subject, content);
            log.setStatus("SENT");
        } catch (RuntimeException ex) {
            log.setStatus("FAILED");
        }
        log.setUpdatedAt(LocalDateTime.now());
        repository.update(log);

        return Map.of("id", log.getId(), "status", log.getStatus());
    }
}
