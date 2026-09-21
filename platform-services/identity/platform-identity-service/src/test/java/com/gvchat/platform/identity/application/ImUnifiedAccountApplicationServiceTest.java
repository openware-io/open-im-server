package com.gvchat.platform.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.platform.identity.infra.persistence.mapper.IdentityAccountMapper;
import com.gvchat.platform.identity.infra.persistence.mapper.LoginIdentityMapper;
import com.gvchat.platform.identity.infra.persistence.mapper.OauthLinkMapper;
import com.gvchat.platform.identity.infra.persistence.po.IdentityAccountPo;
import com.gvchat.platform.identity.infra.persistence.po.LoginIdentityPo;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * IM 统一账号落点：查询 + 清理。
 *
 * <p>IM 用户删除是 IM 自己的业务，不受 SaaS 域口径约束：本服务**不做任何拒绝**，
 * 只清 IM 身份；账号本体仅在「客户账号 + 孤儿」时才删，员工 / 平台运营账号一律保留。
 */
class ImUnifiedAccountApplicationServiceTest {

  private final IdentityAccountMapper accountMapper = mock(IdentityAccountMapper.class);
  private final LoginIdentityMapper loginIdentityMapper = mock(LoginIdentityMapper.class);
  private final OauthLinkMapper oauthLinkMapper = mock(OauthLinkMapper.class);
  private final ImUnifiedAccountApplicationService service =
      new ImUnifiedAccountApplicationService(accountMapper, loginIdentityMapper, oauthLinkMapper);

  @Test
  void shouldPurgeImIdentityAndOrphanCustomerAccount() {
    Long identityId = 7L;
    Long accountId = 71L;
    when(loginIdentityMapper.selectList(any())).thenReturn(List.of(identity(identityId, accountId)));
    when(accountMapper.selectById(accountId)).thenReturn(account(accountId, "CUSTOMER"));
    when(oauthLinkMapper.delete(any())).thenReturn(1);
    when(loginIdentityMapper.deleteById(identityId)).thenReturn(1);
    // 清完后客户账号已无任何落点 → 孤儿账号一并删除。
    when(loginIdentityMapper.selectCount(any())).thenReturn(0L);
    when(oauthLinkMapper.selectCount(any())).thenReturn(0L);
    when(accountMapper.deleteById(accountId)).thenReturn(1);

    var result = service.purgeByImUsername("im_71");

    assertThat(result.loginIdentities()).isEqualTo(1);
    assertThat(result.oauthLinks()).isEqualTo(1);
    assertThat(result.deletedAccount()).isTrue();
    assertThat(result.accountType()).isEqualTo("CUSTOMER");
    verify(accountMapper).deleteById(accountId);
    verify(loginIdentityMapper, never()).insert(any(LoginIdentityPo.class));
  }

  @Test
  void shouldKeepEmployeeAccountBodyAndOnlyUnbindImIdentity() {
    Long identityId = 7L;
    Long accountId = 71L;
    when(loginIdentityMapper.selectList(any())).thenReturn(List.of(identity(identityId, accountId)));
    when(accountMapper.selectById(accountId)).thenReturn(account(accountId, "EMPLOYEE"));
    when(oauthLinkMapper.delete(any())).thenReturn(1);
    when(loginIdentityMapper.deleteById(identityId)).thenReturn(1);

    var result = service.purgeByImUsername("im_71");

    // IM 身份清掉、员工账号本体保留（等后台换绑新 IM 账号），不返回任何拒绝。
    assertThat(result.loginIdentities()).isEqualTo(1);
    assertThat(result.oauthLinks()).isEqualTo(1);
    assertThat(result.deletedAccount()).isFalse();
    assertThat(result.accountType()).isEqualTo("EMPLOYEE");
    verify(accountMapper, never()).deleteById(any(Long.class));
  }

  @Test
  void shouldKeepPlatformOperatorAccountBody() {
    Long identityId = 9L;
    Long accountId = 100L;
    when(loginIdentityMapper.selectList(any())).thenReturn(List.of(identity(identityId, accountId)));
    when(accountMapper.selectById(accountId)).thenReturn(account(accountId, "PLATFORM_OPERATOR"));
    when(oauthLinkMapper.delete(any())).thenReturn(0);
    when(loginIdentityMapper.deleteById(identityId)).thenReturn(1);

    var result = service.purgeByImUsername("a380-admin");

    assertThat(result.deletedAccount()).isFalse();
    assertThat(result.accountType()).isEqualTo("PLATFORM_OPERATOR");
    verify(accountMapper, never()).deleteById(any(Long.class));
  }

  @Test
  void shouldKeepCustomerAccountWhenOtherIdentitiesRemain() {
    Long identityId = 7L;
    Long accountId = 71L;
    when(loginIdentityMapper.selectList(any())).thenReturn(List.of(identity(identityId, accountId)));
    when(accountMapper.selectById(accountId)).thenReturn(account(accountId, "CUSTOMER"));
    when(oauthLinkMapper.delete(any())).thenReturn(1);
    when(loginIdentityMapper.deleteById(identityId)).thenReturn(1);
    // 账号还有手机号登录标识 → 不是孤儿，客户账号本体保留。
    when(loginIdentityMapper.selectCount(any())).thenReturn(1L);
    when(oauthLinkMapper.selectCount(any())).thenReturn(0L);

    var result = service.purgeByImUsername("im_71");

    assertThat(result.deletedAccount()).isFalse();
    verify(accountMapper, never()).deleteById(any(Long.class));
  }

  @Test
  void shouldReturnNotFoundViewWhenNoImIdentity() {
    when(loginIdentityMapper.selectList(any())).thenReturn(List.of());

    var view = service.findByImUsername("im_999");

    assertThat(view.found()).isFalse();
    assertThat(view.accountId()).isNull();
  }

  @Test
  void purgeShouldBeIdempotentWhenIdentityAlreadyGone() {
    when(loginIdentityMapper.selectList(any())).thenReturn(List.of());

    var result = service.purgeByImUsername("im_71");

    assertThat(result.loginIdentities()).isZero();
    assertThat(result.oauthLinks()).isZero();
    assertThat(result.deletedAccount()).isFalse();
    assertThat(result.accountType()).isNull();
    verify(loginIdentityMapper, never()).deleteById(any(Long.class));
  }

  private static LoginIdentityPo identity(Long id, Long accountId) {
    LoginIdentityPo po = new LoginIdentityPo();
    po.setId(id);
    po.setAccountId(accountId);
    po.setLoginType("IM");
    po.setLoginIdentifier("im_" + accountId);
    po.setStatus("ACTIVE");
    return po;
  }

  private static IdentityAccountPo account(Long id, String accountType) {
    IdentityAccountPo po = new IdentityAccountPo();
    po.setId(id);
    po.setStatus("ACTIVE");
    po.setAccountType(accountType);
    return po;
  }
}
