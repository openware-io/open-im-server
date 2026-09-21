package com.gvchat.common.mail.infra.persistence.repository;

import com.gvchat.common.mail.domain.model.MailSendLog;
import com.gvchat.common.mail.domain.repository.MailSendLogRepository;
import com.gvchat.common.mail.infra.persistence.mapper.MailSendLogMapper;
import com.gvchat.common.mail.infra.persistence.po.MailSendLogPo;
import org.springframework.stereotype.Repository;

/** 邮件发送日志仓储实现：MyBatis-Plus 持久化，领域对象与 PO 双向转换。 */
@Repository
public class MailSendLogRepositoryImpl implements MailSendLogRepository {
    private final MailSendLogMapper mapper;

    public MailSendLogRepositoryImpl(MailSendLogMapper mapper) { this.mapper = mapper; }

    @Override
    public MailSendLog save(MailSendLog log) {
        MailSendLogPo po = toPo(log);
        mapper.insert(po);
        return toDomain(po);
    }

    @Override
    public void update(MailSendLog log) {
        mapper.updateById(toPo(log));
    }

    private MailSendLogPo toPo(MailSendLog log) {
        MailSendLogPo po = new MailSendLogPo();
        po.setId(log.getId());
        po.setTenantId(log.getTenantId());
        po.setRecipientDigest(log.getRecipientDigest());
        po.setRecipientMasked(log.getRecipientMasked());
        po.setTemplateCode(log.getTemplateCode());
        po.setSubject(log.getSubject());
        po.setStatus(log.getStatus());
        po.setRetryCount(log.getRetryCount());
        po.setProviderMessageId(log.getProviderMessageId());
        po.setRawResponse(log.getRawResponse());
        po.setIdempotencyKey(log.getIdempotencyKey());
        po.setCreatedAt(log.getCreatedAt());
        po.setUpdatedAt(log.getUpdatedAt());
        return po;
    }

    private MailSendLog toDomain(MailSendLogPo po) {
        MailSendLog log = new MailSendLog();
        log.setId(po.getId());
        log.setTenantId(po.getTenantId());
        log.setRecipientDigest(po.getRecipientDigest());
        log.setRecipientMasked(po.getRecipientMasked());
        log.setTemplateCode(po.getTemplateCode());
        log.setSubject(po.getSubject());
        log.setStatus(po.getStatus());
        log.setRetryCount(po.getRetryCount());
        log.setProviderMessageId(po.getProviderMessageId());
        log.setRawResponse(po.getRawResponse());
        log.setIdempotencyKey(po.getIdempotencyKey());
        log.setCreatedAt(po.getCreatedAt());
        log.setUpdatedAt(po.getUpdatedAt());
        return log;
    }
}
