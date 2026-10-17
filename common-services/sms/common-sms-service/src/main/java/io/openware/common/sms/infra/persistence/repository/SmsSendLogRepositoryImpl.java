package io.openware.common.sms.infra.persistence.repository;

import io.openware.common.sms.domain.model.SmsSendLog;
import io.openware.common.sms.domain.repository.SmsSendLogRepository;
import io.openware.common.sms.infra.persistence.mapper.SmsSendLogMapper;
import io.openware.common.sms.infra.persistence.po.SmsSendLogPo;
import java.time.LocalDateTime;
import org.springframework.stereotype.Repository;

/**
 * 短信发送日志仓储实现：MyBatis-Plus 持久化，领域对象与 PO 双向转换。
 *
 * <p><b>审计字段在本层填充</b>（standards/10 §聚合装配与持久化契约）：{@code created_at}/{@code updated_at}
 * 是记账信息，不进领域模型。两条写路径的口径与改造前逐字一致——{@code save} 同时写两列，
 * {@code update} 只刷新 {@code updated_at}（创建时刻不可变）。
 */
@Repository
public class SmsSendLogRepositoryImpl implements SmsSendLogRepository {
    private final SmsSendLogMapper mapper;

    public SmsSendLogRepositoryImpl(SmsSendLogMapper mapper) { this.mapper = mapper; }

    @Override
    public SmsSendLog save(SmsSendLog log) {
        LocalDateTime now = LocalDateTime.now();
        SmsSendLogPo po = toPo(log);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        mapper.insert(po);
        return toDomain(po).withId(po.getId());
    }

    @Override
    public void update(SmsSendLog log) {
        SmsSendLogPo po = toPo(log);
        // 只刷新 updated_at：created_at 是创建时刻，不能因一次状态变更被改写。
        po.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(po);
    }

    private SmsSendLogPo toPo(SmsSendLog log) {
        SmsSendLogPo po = new SmsSendLogPo();
        po.setId(log.id());
        po.setTenantId(log.tenantId());
        po.setPhoneDigest(log.phoneDigest());
        po.setPhoneMasked(log.phoneMasked());
        po.setTemplateCode(log.templateCode());
        po.setProvider(log.provider());
        po.setStatus(log.status());
        po.setRetryCount(log.retryCount());
        po.setProviderMessageId(log.providerMessageId());
        po.setRawResponse(log.rawResponse());
        po.setIdempotencyKey(log.idempotencyKey());
        return po;
    }

    private SmsSendLog toDomain(SmsSendLogPo po) {
        return new SmsSendLog(po.getId(), po.getTenantId(), po.getPhoneDigest(), po.getPhoneMasked(),
                po.getTemplateCode(), po.getProvider(), po.getStatus(), po.getRetryCount(),
                po.getProviderMessageId(), po.getRawResponse(), po.getIdempotencyKey());
    }
}
