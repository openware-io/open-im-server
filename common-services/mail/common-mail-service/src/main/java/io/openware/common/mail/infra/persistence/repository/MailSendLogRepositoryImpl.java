package io.openware.common.mail.infra.persistence.repository;

import io.openware.common.mail.domain.model.MailSendLog;
import io.openware.common.mail.domain.repository.MailSendLogRepository;
import io.openware.common.mail.infra.persistence.mapper.MailSendLogMapper;
import io.openware.common.mail.infra.persistence.po.MailSendLogPo;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

/**
 * 邮件发送日志仓储实现：MyBatis-Plus 持久化，领域对象与 PO 双向转换。
 *
 * <p><b>审计字段在本层填充</b>（standards/10 §聚合装配与持久化契约）：{@code created_at}/{@code updated_at}
 * 是记账信息，不进领域模型。两条写路径的口径与改造前逐字一致——{@code save} 同时写两列，
 * {@code update} 只刷新 {@code updated_at}（创建时刻不可变）。
 */
@Repository
public class MailSendLogRepositoryImpl implements MailSendLogRepository {
    private final MailSendLogMapper mapper;

    public MailSendLogRepositoryImpl(MailSendLogMapper mapper) { this.mapper = mapper; }

    @Override
    public MailSendLog save(MailSendLog log) {
        LocalDateTime now = LocalDateTime.now();
        MailSendLogPo po = toPo(log);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        mapper.insert(po);
        return toDomain(po).withId(po.getId());
    }

    @Override
    public void update(MailSendLog log) {
        MailSendLogPo po = toPo(log);
        // 只刷新 updated_at：created_at 是创建时刻，不能因一次状态变更被改写。
        po.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(po);
    }

    private MailSendLogPo toPo(MailSendLog log) {
        MailSendLogPo po = new MailSendLogPo();
        po.setId(log.id());
        po.setTenantId(log.tenantId());
        po.setRecipientDigest(log.recipientDigest());
        po.setRecipientMasked(log.recipientMasked());
        po.setTemplateCode(log.templateCode());
        po.setSubject(log.subject());
        po.setStatus(log.status());
        po.setRetryCount(log.retryCount());
        po.setProviderMessageId(log.providerMessageId());
        po.setRawResponse(log.rawResponse());
        po.setIdempotencyKey(log.idempotencyKey());
        return po;
    }

    private MailSendLog toDomain(MailSendLogPo po) {
        return new MailSendLog(po.getId(), po.getTenantId(), po.getRecipientDigest(), po.getRecipientMasked(),
                po.getTemplateCode(), po.getSubject(), po.getStatus(), po.getRetryCount(),
                po.getProviderMessageId(), po.getRawResponse(), po.getIdempotencyKey());
    }
}
