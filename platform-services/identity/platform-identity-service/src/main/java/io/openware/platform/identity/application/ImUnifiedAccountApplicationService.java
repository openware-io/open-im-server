package io.openware.platform.identity.application;

import io.openware.platform.identity.domain.account.model.IdentityAccount;
import io.openware.platform.identity.domain.account.model.LoginIdentity;
import io.openware.platform.identity.domain.account.repository.IdentityAccountRepository;
import io.openware.platform.identity.domain.account.repository.LoginIdentityRepository;
import io.openware.platform.identity.domain.account.repository.OauthLinkRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 统一账号模型里「IM 身份」的只读查询实现。
 *
 * <p>该服务仅提供统一身份侧的查询能力；IM 用户删除不得跨业务域物理删除 SaaS 或审计数据。
 *
 * <p>身份解绑由各业务的独立流程处理，本服务不作为 IM 删除的级联入口。
 *
 * <p>只读视图按 standards/10 走端口：查询条件是业务语义（"某 IM 用户名下的登录标识"），不再在应用层
 * 拼接 Wrapper，也不直接承载持久化对象。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImUnifiedAccountApplicationService {

  /** IM 登录标识类型与对应的 OAuth provider（两者取值都是 {@code IM}）。 */
  public static final String IM_LOGIN_TYPE = "IM";
  private final IdentityAccountRepository accountRepository;
  private final LoginIdentityRepository loginIdentityRepository;
  private final OauthLinkRepository oauthLinkRepository;

  /** 按 IM 用户名（{@code im_<id>}）查统一账号；不存在返回 {@code found=false}。 */
  public ImAccountView findByImUsername(String username) {
    List<LoginIdentity> identities = imIdentities(username);
    if (identities.isEmpty()) {
      return new ImAccountView(false, null, null, 0, 0);
    }
    Long accountId = identities.get(0).accountId();
    IdentityAccount account = accountId == null ? null : accountRepository.findById(accountId).orElse(null);
    return new ImAccountView(true, accountId, account == null ? null : account.accountType(),
        identities.size(), countImOauthLinks(username));
  }

  private List<LoginIdentity> imIdentities(String username) {
    if (username == null || username.isBlank()) {
      return List.of();
    }
    return loginIdentityRepository.findAllByLoginIdentifier(IM_LOGIN_TYPE, username.trim());
  }

  private long countImOauthLinks(String username) {
    if (username == null || username.isBlank()) {
      return 0;
    }
    return oauthLinkRepository.findAllByProviderAccount(IM_LOGIN_TYPE, username.trim()).size();
  }

  /**
   * 统一账号视图：{@code found=false} 表示该 IM 用户名在统一账号模型里没有落点
   * （此时 IM 后台可以安全删除，不存在 SaaS 侧残留）。
   */
  public record ImAccountView(boolean found, Long accountId, String accountType, int loginIdentities,
                              long oauthLinks) {
  }

}
