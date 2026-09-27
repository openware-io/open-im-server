package io.openware.platform.identity.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.openware.platform.identity.infra.persistence.mapper.IdentityAccountMapper;
import io.openware.platform.identity.infra.persistence.mapper.LoginIdentityMapper;
import io.openware.platform.identity.infra.persistence.mapper.OauthLinkMapper;
import io.openware.platform.identity.infra.persistence.po.IdentityAccountPo;
import io.openware.platform.identity.infra.persistence.po.LoginIdentityPo;
import io.openware.platform.identity.infra.persistence.po.OauthLinkPo;
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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImUnifiedAccountApplicationService {

  /** IM 登录标识类型与对应的 OAuth provider（两者取值都是 {@code IM}）。 */
  public static final String IM_LOGIN_TYPE = "IM";
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

  /**
   * 统一账号视图：{@code found=false} 表示该 IM 用户名在统一账号模型里没有落点
   * （此时 IM 后台可以安全删除，不存在 SaaS 侧残留）。
   */
  public record ImAccountView(boolean found, Long accountId, String accountType, int loginIdentities,
                              long oauthLinks) {
  }

}
