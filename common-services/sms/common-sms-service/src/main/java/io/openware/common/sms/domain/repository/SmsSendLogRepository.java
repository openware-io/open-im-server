package io.openware.common.sms.domain.repository;

import io.openware.common.sms.domain.model.SmsSendLog;

/** 短信发送日志仓储契约。 */
public interface SmsSendLogRepository {
    SmsSendLog save(SmsSendLog log);

    void update(SmsSendLog log);
}
