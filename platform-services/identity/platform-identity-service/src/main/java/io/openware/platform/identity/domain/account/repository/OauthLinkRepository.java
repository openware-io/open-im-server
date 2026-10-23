package io.openware.platform.identity.domain.account.repository;

import io.openware.platform.identity.domain.account.model.OauthLink;
import java.util.List;
import java.util.Optional;

/**
 * 第三方登录绑定仓储端口。
 *
 * <p>以聚合为单位，方法表达业务意图；不暴露 MyBatis Wrapper、PO、Mapper 或分页类型。
 *
 * <p>按 {@code (provider, providerAccountId)} 查询是绑定流程的权威入口：该组合上的唯一性由数据库约束保证。
 */
public interface OauthLinkRepository {

  /** 按提供方 + 应用 + 提供方账号标识读取绑定（绑定流程的权威入口）。 */
  Optional<OauthLink> findByProviderAccount(String provider, String appId, String providerAccountId);

  /** 兼容旧调用：默认 appId=legacy。 */
  default Optional<OauthLink> findByProviderAccount(String provider, String providerAccountId) {
    return findByProviderAccount(provider, OauthLink.LEGACY_APP_ID, providerAccountId);
  }

  /** 列出某账号下的全部第三方绑定。 */
  List<OauthLink> findByAccountId(Long accountId);

  /** 按提供方 + 应用 + 提供方账号标识列出全部绑定。 */
  List<OauthLink> findAllByProviderAccount(String provider, String appId, String providerAccountId);

  /** 兼容旧调用：默认 appId=legacy。 */
  default List<OauthLink> findAllByProviderAccount(String provider, String providerAccountId) {
    return findAllByProviderAccount(provider, OauthLink.LEGACY_APP_ID, providerAccountId);
  }

  /** 保存绑定：{@code id} 为空时新增，否则更新。 */
  OauthLink save(OauthLink link);
}
