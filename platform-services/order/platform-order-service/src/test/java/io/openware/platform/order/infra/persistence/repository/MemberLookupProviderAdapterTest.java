package io.openware.platform.order.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openware.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 跨上下文会员查询适配器（order 域声明的只读端口 → customer 域的表）。
 *
 * <p>这条依赖值得有测试，不是因为翻译复杂，而是因为它的失败模式是**权限**而非功能：
 * 端口只按 {@code (tenant_id, account_id)} 定位，而 {@code account_id} 在租户间不保证唯一。
 * 少了租户条件就会解析出**别的租户的会员**，读路径随之放行——这不是"查不到"，
 * 而是"查到了不该看到的"。
 *
 * <p>另外钉住空值短路：租户或账号缺失时**不查库**并返回空，调用方据此按"解析不出会员"处理；
 * 若把它当成一次查询，空租户可能被 SQL 当成通配，把某个会员返回给没有上下文可言的调用方。
 */
class MemberLookupProviderAdapterTest {

  private static final long TENANT_ID = 100L;
  private static final long ACCOUNT_ID = 7L;

  private final CustomerLookupMapper mapper = mock(CustomerLookupMapper.class);
  private final MemberLookupProviderAdapter adapter = new MemberLookupProviderAdapter(mapper);

  @Test
  void findMemberIdPassesBothTenantAndAccount() {
    when(mapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(42L);

    assertEquals(Optional.of(42L), adapter.findMemberId(TENANT_ID, ACCOUNT_ID));

    // 两个参数都必须原样下传：只传 accountId 会跨租户解析出别人的会员。
    verify(mapper).findMemberId(TENANT_ID, ACCOUNT_ID);
  }

  @Test
  void findMemberIdReturnsEmptyWhenNoRow() {
    when(mapper.findMemberId(TENANT_ID, ACCOUNT_ID)).thenReturn(null);

    assertTrue(adapter.findMemberId(TENANT_ID, ACCOUNT_ID).isEmpty());
  }

  /** 租户缺失时不查库：没有租户上下文就没有合法的作用域，不能退回"只按账号查"。 */
  @Test
  void findMemberIdShortCircuitsOnMissingTenant() {
    assertTrue(adapter.findMemberId(null, ACCOUNT_ID).isEmpty());

    verifyNoInteractions(mapper);
  }

  /** 账号缺失时不查库（0 或负值同属缺失，但端口只判 null，保持与既有实现一致）。 */
  @Test
  void findMemberIdShortCircuitsOnMissingAccount() {
    assertTrue(adapter.findMemberId(TENANT_ID, null).isEmpty());

    verifyNoInteractions(mapper);
  }
}
