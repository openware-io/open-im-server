package com.gvchat.common.mail.domain.repository;

import com.gvchat.common.mail.domain.model.MailSendLog;

/** 邮件发送日志仓储契约。 */
public interface MailSendLogRepository {
    MailSendLog save(MailSendLog log);

    void update(MailSendLog log);
}
