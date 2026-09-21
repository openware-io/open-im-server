package com.gvchat.platform.marketing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.platform.marketing.infra.persistence.mapper.ConsentMapper;
import com.gvchat.platform.marketing.infra.persistence.po.MktConsentPo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 营销同意应用服务（脚手架）。营销授权与履约通知按 consent_type 分开。
 */
@Service
public class ConsentApplicationService {
    private final ConsentMapper consentMapper;

    public ConsentApplicationService(ConsentMapper consentMapper) { this.consentMapper = consentMapper; }

    public List<MktConsentPo> list(Long accountId) {
        if (accountId == null) throw new ApiException(400, "ACCOUNT_REQUIRED", "缺少 accountId");
        LambdaQueryWrapper<MktConsentPo> qw = new LambdaQueryWrapper<>();
        qw.eq(MktConsentPo::getAccountId, accountId).orderByDesc(MktConsentPo::getId);
        return consentMapper.selectList(qw);
    }

    @Transactional
    public MktConsentPo upsert(Long accountId, String consentType, String channel, Boolean granted,
                               String policyVersion, String source) {
        if (accountId == null) throw new ApiException(400, "ACCOUNT_REQUIRED", "缺少 accountId");
        String type = consentType == null || consentType.isBlank() ? "MARKETING" : consentType;
        String chan = channel == null || channel.isBlank() ? "APP" : channel;
        boolean grant = granted == null || granted;
        // 审计占位：consent 变更(grant/withdraw)需写入审计轨迹（或 Outbox 事件），真实实现见 SAAS_PLATFORM_06_TECHNICAL.md §8
        MktConsentPo existing = find(accountId, type, chan);
        if (existing != null) {
            existing.setGranted(grant ? 1 : 0);
            existing.setPolicyVersion(policyVersion);
            existing.setWithdrawnAt(grant ? null : LocalDateTime.now());
            existing.setVersion(existing.getVersion() == null ? 1 : existing.getVersion() + 1);
            existing.setUpdatedAt(LocalDateTime.now());
            consentMapper.updateById(existing);
            return existing;
        }
        MktConsentPo po = new MktConsentPo();
        po.setAccountId(accountId);
        po.setTenantId(TenantContextHolder.tenantIdOrNull());
        po.setConsentType(type);
        po.setChannel(chan);
        po.setPolicyVersion(policyVersion == null ? "1.0" : policyVersion);
        po.setGranted(grant ? 1 : 0);
        po.setSource(source == null ? "BUSINESS" : source);
        po.setOccurredAt(LocalDateTime.now());
        po.setWithdrawnAt(grant ? null : LocalDateTime.now());
        po.setVersion(0);
        po.setCreatedAt(LocalDateTime.now());
        po.setUpdatedAt(LocalDateTime.now());
        consentMapper.insert(po);
        return po;
    }

    private MktConsentPo find(Long accountId, String consentType, String channel) {
        LambdaQueryWrapper<MktConsentPo> qw = new LambdaQueryWrapper<>();
        qw.eq(MktConsentPo::getAccountId, accountId)
          .eq(MktConsentPo::getConsentType, consentType)
          .eq(MktConsentPo::getChannel, channel)
          .last("LIMIT 1");
        return consentMapper.selectOne(qw);
    }
}
