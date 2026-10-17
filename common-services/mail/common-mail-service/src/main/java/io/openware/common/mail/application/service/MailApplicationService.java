package io.openware.common.mail.application.service;

import io.openware.common.mail.domain.RecipientPrivacy;
import io.openware.common.mail.domain.model.MailSendLog;
import io.openware.common.mail.domain.repository.MailSendLogRepository;
import io.openware.common.mail.infra.provider.MailProvider;
import org.springframework.stereotype.Service;

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

        MailSendLog log = MailSendLog.pending(((Number) body.getOrDefault("tenantId", 0)).longValue(),
                RecipientPrivacy.digest(to), RecipientPrivacy.mask(to), templateCode, subject, idempotencyKey);
        log = repository.save(log);

        try {
            mailProvider.send(from, to, subject, content);
            // 状态迁移收在领域模型上（markSent/markFailed），应用层只决定走哪条分支。
            log = log.markSent(null, null);
        } catch (RuntimeException ex) {
            log = log.markFailed(null);
        }
        repository.update(log);

        return Map.of("id", log.id(), "status", log.status());
    }
}
