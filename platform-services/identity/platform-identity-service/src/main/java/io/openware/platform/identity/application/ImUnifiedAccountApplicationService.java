package io.openware.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.openware.platform.identity.infra.persistence.mapper.IdentityAccountMapper;
import io.openware.platform.identity.infra.persistence.mapper.LoginIdentityMapper;
import io.openware.platform.identity.infra.persistence.mapper.OauthLinkMapper;
import io.openware.platform.identity.infra.persistence.po.IdentityAccountPo;
import io.openware.platform.identity.infra.persistence.po.LoginIdentityPo;
import io.openware.platform.identity.infra.persistence.po.OauthLinkPo;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一账号模型里「IM 身份」的运维端点实现：查询与清理。
 *
 * <p>背景：IM 后台「删除用户」是 IM 自己的业务（IM 账号被硬删 + 墓碑化），同一个自然人在统一账号模型里
 * 还留着 {@code idt_login_identity(login_type='IM', login_identifier='im_<id>')} 与 {@code idt_oauth_link}；
 * 不清掉的话 SaaS 侧仍按原 IM 标识解析得出这个账号，且该标识无法被新 IM 账号复用。
 *
 * <p>**IM 用户的删除不受 SaaS 域口径约束**（员工/平台运营与 IM 账号的关联由后台「换绑 IM 账号」维护，
 * 不是靠禁止删除），因此本服务**不做任何拒绝**，只清理 IM 身份：
 * <ol>
 *   <li>物理删除该 IM 用户名的 {@code idt_login_identity} 行与对应 {@code idt_oauth_link} 行；
 *       删除后该员工/后台账号处于「未绑定 IM」，等后台换绑新的 IM 账号即可；</li>
 *   <li>{@code idt_account} **只在它是客户账号（{@code account_type=CUSTOMER}）且已无任何其它登录标识与
 *       OAuth 绑定（孤儿）时**才删；</li>
 *   <li>{@code EMPLOYEE} / {@code PLATFORM_OPERATOR} 的账号本体**一律保留**——那是 SaaS 自己的记录
 *       （员工 / 平台运营账号），删它才是真风险。</li>
 * </ol>
 *
 * <p>返回值里的 {@code deletedAccount} / {@code accountType} 显式说明「账号本体有没有被删」，
 * 让调用方与审计能看出「IM 身份已清、员工账号本体保留」的差异。
 *
 * <p>**只有本服务拥有 open_im 的 idt_* 表**，清理只能由本服务执行；调用方通过内部端点在
 * **自己的事务里**发起（IM 侧失败则整体回滚，不留半删状态）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImUnifiedAccountApplicationService {

  /** IM 登录标识类型与对应的 OAuth provider（两者取值都是 {@code IM}）。 */
  public static final String IM_LOGIN_TYPE = "IM";
  /** 可随 IM 身份一起物理删除的账号类型：只有客户账号。 */
  private static final String CUSTOMER_ACCOUNT_TYPE = "CUSTOMER";

  private final IdentityAccountMapper accountMapper;
  private final LoginIdentityMapper loginIdentityMapper;
  private final OauthLinkMapper oauthLinkMapper;

  /** 按 IM 用户名（{@code im_<id>}）查统一账号；不存在返回 {@code found=false}。 */
  public ImAccountView findByImUsername(String username) {
    List<LoginIdentityPo> identities = imIdentities(username);
    if (identities.isEmpty()) {
      return new ImAccountView(false, null, null, 0, 0);
    }
    Long accountId = identities.get(0).getAccountId();
    IdentityAccountPo account = accountId == null ? null : accountMapper.selectById(accountId);
    return new ImAccountView(true, accountId, account == null ? null : account.getAccountType(),
        identities.size(), countImOauthLinks(username));
  }

  /**
   * 清理某个 IM 用户名在统一账号模型里的落点（幂等）：删 IM 登录标识与 OAuth 绑定，
   * 客户账号在成为孤儿时一并删除；员工 / 平台运营账号本体保留。
   */
  @Transactional
  public ImAccountPurgeResult purgeByImUsername(String username) {
    List<LoginIdentityPo> identities = imIdentities(username);
    if (identities.isEmpty()) {
      // 幂等：第二次调用时该 IM 标识已不存在，直接返回 0 计数（不报错、不 500）。
      return new ImAccountPurgeResult(0, 0, false, null);
    }
    Set<Long> accountIds = new LinkedHashSet<>();
    for (LoginIdentityPo identity : identities) {
      if (identity.getAccountId() != null) {
        accountIds.add(identity.getAccountId());
      }
    }
    int oauthLinks = deleteImOauthLinks(username);
    int loginIdentities = 0;
    for (LoginIdentityPo identity : identities) {
      loginIdentities += loginIdentityMapper.deleteById(identity.getId());
    }

    // 账号本体：只有客户账号且已成为孤儿才删；员工/平台运营账号一律保留（IM 绑定已解除，等后台换绑）。
    boolean deletedAccount = false;
    String accountType = null;
    for (Long accountId : accountIds) {
      IdentityAccountPo account = accountMapper.selectById(accountId);
      if (account == null) {
        continue;
      }
      accountType = account.getAccountType();
      if (CUSTOMER_ACCOUNT_TYPE.equals(account.getAccountType()) && isOrphan(accountId)) {
        deletedAccount = accountMapper.deleteById(accountId) > 0 || deletedAccount;
      }
    }
    log.info("清理 IM 统一账号落点完成, username={}, loginIdentities={}, oauthLinks={}, "
            + "deletedAccount={}, accountType={}",
        username, loginIdentities, oauthLinks, deletedAccount, accountType);
    return new ImAccountPurgeResult(loginIdentities, oauthLinks, deletedAccount, accountType);
  }

  /** 账号是否已成为孤儿：没有任何登录标识、也没有任何 OAuth 绑定。 */
  private boolean isOrphan(Long accountId) {
    Long identities = loginIdentityMapper.selectCount(
        new QueryWrapper<LoginIdentityPo>().eq("account_id", accountId));
    Long links = oauthLinkMapper.selectCount(
        new QueryWrapper<OauthLinkPo>().eq("account_id", accountId));
    return (identities == null || identities == 0) && (links == null || links == 0);
  }

  private List<LoginIdentityPo> imIdentities(String username) {
    if (username == null || username.isBlank()) {
      return List.of();
    }
    return loginIdentityMapper.selectList(new QueryWrapper<LoginIdentityPo>()
        .eq("login_type", IM_LOGIN_TYPE).eq("login_identifier", username.trim()));
  }

  private long countImOauthLinks(String username) {
    Long count = oauthLinkMapper.selectCount(new QueryWrapper<OauthLinkPo>()
        .eq("provider", IM_LOGIN_TYPE).eq("provider_account_id", username.trim()));
    return count == null ? 0 : count;
  }

  private int deleteImOauthLinks(String username) {
    return oauthLinkMapper.delete(new QueryWrapper<OauthLinkPo>()
        .eq("provider", IM_LOGIN_TYPE).eq("provider_account_id", username.trim()));
  }

  /**
   * 统一账号视图：{@code found=false} 表示该 IM 用户名在统一账号模型里没有落点
   * （此时 IM 后台可以安全删除，不存在 SaaS 侧残留）。
   */
  public record ImAccountView(boolean found, Long accountId, String accountType, int loginIdentities,
                              long oauthLinks) {
  }

  /**
   * 清理结果。
   *
   * @param loginIdentities 物理删除的 IM 登录标识行数
   * @param oauthLinks      物理删除的 IM OAuth 绑定行数
   * @param deletedAccount  账号本体是否被删除（只有「客户账号 + 孤儿」才会 true；员工/平台运营账号恒 false）
   * @param accountType     涉及的账号类型（EMPLOYEE / CUSTOMER / PLATFORM_OPERATOR；无账号时 null）
   */
  public record ImAccountPurgeResult(int loginIdentities, int oauthLinks, boolean deletedAccount,
                                     String accountType) {
  }
}
