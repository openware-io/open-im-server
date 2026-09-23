package io.openware.common.sms.infra.persistence.repository;

import io.openware.common.sms.domain.model.SmsSendLog;
import io.openware.common.sms.domain.repository.SmsSendLogRepository;
import io.openware.common.sms.infra.persistence.mapper.SmsSendLogMapper;
import io.openware.common.sms.infra.persistence.po.SmsSendLogPo;
import org.springframework.stereotype.Repository;

/** 短信发送日志仓储实现：MyBatis-Plus 持久化，领域对象与 PO 双向转换。 */
@Repository
public class SmsSendLogRepositoryImpl implements SmsSendLogRepository {
    private final SmsSendLogMapper mapper;

    public SmsSendLogRepositoryImpl(SmsSendLogMapper mapper) { this.mapper = mapper; }

    @Override
    public SmsSendLog save(SmsSendLog log) {
        SmsSendLogPo po = toPo(log);
        mapper.insert(po);
        return toDomain(po);
    }

    @Override
    public void update(SmsSendLog log) {
        mapper.updateById(toPo(log));
    }

    private SmsSendLogPo toPo(SmsSendLog log) {
        SmsSendLogPo po = new SmsSendLogPo();
        po.setId(log.getId());
        po.setTenantId(log.getTenantId());
        po.setPhoneDigest(log.getPhoneDigest());
        po.setPhoneMasked(log.getPhoneMasked());
        po.setTemplateCode(log.getTemplateCode());
        po.setProvider(log.getProvider());
        po.setStatus(log.getStatus());
        po.setRetryCount(log.getRetryCount());
        po.setProviderMessageId(log.getProviderMessageId());
        po.setRawResponse(log.getRawResponse());
        po.setIdempotencyKey(log.getIdempotencyKey());
        po.setCreatedAt(log.getCreatedAt());
        po.setUpdatedAt(log.getUpdatedAt());
        return po;
    }

    private SmsSendLog toDomain(SmsSendLogPo po) {
        SmsSendLog log = new SmsSendLog();
        log.setId(po.getId());
        log.setTenantId(po.getTenantId());
        log.setPhoneDigest(po.getPhoneDigest());
        log.setPhoneMasked(po.getPhoneMasked());
        log.setTemplateCode(po.getTemplateCode());
        log.setProvider(po.getProvider());
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
