package io.openware.platform.marketing.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.marketing.domain.consent.model.MktConsent;
import io.openware.platform.marketing.domain.consent.repository.MktConsentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 营销同意应用服务（脚手架）。营销授权与履约通知按 consent_type 分开。
 *
 * <p>持久化经领域端口；类型／渠道／政策版本／来源的回退口径与授予-撤回的状态迁移（含版本自增、
 * 撤回时间维护）都由领域模型负责，应用层只做编排与事务边界。
 */
@Service
public class ConsentApplicationService {
    private final MktConsentRepository consentRepository;

    public ConsentApplicationService(MktConsentRepository consentRepository) {
        this.consentRepository = consentRepository;
    }

    public List<MktConsent> list(Long accountId) {
        if (accountId == null) throw new ApiException(400, "ACCOUNT_REQUIRED", "缺少 accountId");
        return consentRepository.listByAccount(accountId);
    }

    @Transactional
    public MktConsent upsert(Long accountId, String consentType, String channel, Boolean granted,
                             String policyVersion, String source) {
        if (accountId == null) throw new ApiException(400, "ACCOUNT_REQUIRED", "缺少 accountId");
        // 审计占位：consent 变更(grant/withdraw)需写入审计轨迹（或 Outbox 事件），真实实现见 SAAS_PLATFORM_06_TECHNICAL.md §8
        boolean grant = granted == null || granted;
        LocalDateTime now = LocalDateTime.now();
        MktConsent existing = consentRepository
                .findUnique(accountId, resolveType(consentType), resolveChannel(channel))
                .orElse(null);
        if (existing != null) {
            return consentRepository.save(existing.changeGrant(grant, policyVersion, now));
        }
        return consentRepository.save(MktConsent.record(accountId, TenantContextHolder.tenantIdOrNull(),
                consentType, channel, policyVersion, source, grant, now));
    }

    /**
     * upsert 的查找键必须与实际写入的口径一致。
     *
     * <p>写入走领域工厂做回退（空值→默认），因此查找也必须先回退，否则空值入参会查不到已存在的记录，
     * 从而插入第二条（同一账号+类型+渠道）记录。
     */
    private static String resolveType(String consentType) {
        return consentType == null || consentType.isBlank() ? MktConsent.TYPE_MARKETING : consentType;
    }

    private static String resolveChannel(String channel) {
        return channel == null || channel.isBlank() ? MktConsent.CHANNEL_APP : channel;
    }
}
