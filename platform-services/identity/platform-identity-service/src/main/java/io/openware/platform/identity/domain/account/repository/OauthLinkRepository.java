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

  /** 按提供方 + 提供方账号标识读取绑定（绑定流程的权威入口）。 */
  Optional<OauthLink> findByProviderAccount(String provider, String providerAccountId);

  /** 列出某账号下的全部第三方绑定。 */
  List<OauthLink> findByAccountId(Long accountId);

  /**
   * 按提供方 + 提供方账号标识列出全部绑定。
   *
   * <p>只读核对场景需要看到实际条数，因此返回列表而不是计数：计数方法会把"确实没有"与"查询写法有误"
   * 混成同一个 0。
   */
  List<OauthLink> findAllByProviderAccount(String provider, String providerAccountId);

  /** 保存绑定：{@code id} 为空时新增，否则更新。 */
  OauthLink save(OauthLink link);
}
