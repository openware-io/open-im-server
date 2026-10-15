package io.openware.platform.marketing.domain.consent.repository;

import io.openware.platform.marketing.domain.consent.model.MktConsent;
import java.util.List;
import java.util.Optional;

/**
 * 营销同意仓储端口。
 *
 * <p>方法表达业务意图；不暴露 MyBatis Wrapper、PO、Mapper 或分页类型。
 */
public interface MktConsentRepository {

  /** 列出某账号的全部同意记录（按标识倒序，保持既有列表口径）。 */
  List<MktConsent> listByAccount(Long accountId);

  /**
   * 按账号 + 同意类型 + 渠道读取唯一记录（upsert 的判据）。
   *
   * <p>该组合上的唯一性由数据库约束保证，因此最多返回一条。
   */
  Optional<MktConsent> findUnique(Long accountId, String consentType, String channel);

  /** 保存同意记录：{@code id} 为空时新增，否则更新，返回落库后的聚合。 */
  MktConsent save(MktConsent consent);
}
