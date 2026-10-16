package io.openware.platform.customer.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.time.TimeRangeParams.TimeRange;
import io.openware.platform.customer.api.dto.MemberView;
import io.openware.platform.customer.application.MemberApplicationService;
import io.openware.platform.customer.application.MemberDisplay;
import io.openware.platform.customer.application.PointApplicationService;
import io.openware.platform.customer.application.WalletApplicationService;
import io.openware.platform.customer.application.WalletTokenDisplayService;
import io.openware.platform.customer.domain.member.model.Member;
import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import io.openware.platform.customer.infra.client.TenantWalletTokenClient;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 客户档案响应的<b>序列化契约</b>：{@code GET /business/members} 此前直接返回持久化对象
 * {@code CstMemberPo}，所以它的每一个字段名都是已发布的契约。
 *
 * <p>本类把这件事钉死在<b>真正被序列化的那个对象</b>上（控制器装配后的 {@code Page<MemberView>}）：
 * 少一个键是破坏，多一个键同样是破坏——尤其是 {@code name} / {@code phone} / {@code accountType} /
 * {@code imAccountName} / {@code imAccountDeleted} 这五个 {@code @TableField(exist = false)} 的展示字段，
 * 它们在旧的响应里有、在视图里必须继续有，但**绝不能**跑进任何 SQL 列引用
 * （那条边界由 {@code MemberRepositoryAdapterTest} 钉住）。
 */
class MemberResponseContractTest {

  private static final long TENANT_ID = 100L;
  private static final long MEMBER_ID = 7L;
  private static final LocalDateTime JOINED_AT = LocalDateTime.of(2026, 9, 1, 10, 0, 0);

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final MemberApplicationService memberService = mock(MemberApplicationService.class);
  private final PointApplicationService pointService = mock(PointApplicationService.class);
  private final WalletApplicationService walletService = mock(WalletApplicationService.class);
  private final TenantWalletTokenClient client = mock(TenantWalletTokenClient.class);

  private MemberController controller;

  @BeforeEach
  void setUp() {
    controller = new MemberController(memberService, pointService, walletService,
        new WalletTokenDisplayService(client));
    // 带门店与账号上下文（TenantContext 的参数序：tenantId, organizationId, storeId, accountId, …）：
    // 建档/绑定要求门店上下文，列表的代币换算要读租户配置（缺省会 NPE）。
    TenantContextHolder.set(new TenantContext(TENANT_ID, null, 2001L, 1L, 0, List.of("member.manage")));
    when(client.resolve(TENANT_ID)).thenReturn(TenantWalletTokenClient.WalletTokenConfig.DEFAULT);
  }

  @AfterEach
  void tearDown() {
    TenantContextHolder.clear();
  }

  /** 列表：分页外形（{@code records/total/size/current/pages}）与行内字段名逐个不变。 */
  @Test
  void memberPageJsonKeepsEveryPersistedFieldName() throws Exception {
    Page<MemberDisplay> page = new Page<>(2, 20, 42L);
    page.setRecords(List.of(display()));
    when(memberService.list(2L, 20L, "张", 3L, "ACTIVE", TimeRange.none())).thenReturn(page);

    Page<MemberView> response = controller.list(2, 20, "张", 3L, "ACTIVE", null, null);
    JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(response));

    // 分页外形照搬原 MyBatis-Plus Page 的字段名
    assertEquals(42L, node.path("total").asLong());
    assertEquals(20L, node.path("size").asLong());
    assertEquals(2L, node.path("current").asLong());
    assertEquals(3L, node.path("pages").asLong());
    assertEquals(1, node.path("records").size());

    JsonNode row = node.path("records").get(0);
    for (String field : List.of("id", "tenantId", "originStoreId", "accountId", "imAccount",
        "imUsername", "imBoundAt", "memberNo", "nameCipher", "phoneCipher", "phoneDigest", "name",
        "phone", "accountType", "imAccountName", "imAccountDeleted", "levelId", "status", "joinedAt",
        "expiresAt", "version", "createdBy", "createdAt", "updatedBy", "updatedAt")) {
      assertTrue(row.has(field), "客户档案响应缺少既有字段: " + field);
    }
    // 值也照搬（不是只留一个 null 键）
    assertEquals(MEMBER_ID, row.path("id").asLong());
    assertEquals("M7", row.path("memberNo").asText());
    assertEquals("张三", row.path("name").asText(), "展示字段是既有响应字段，必须继续回显");
    assertEquals("138****1111", row.path("phone").asText());
    assertEquals("CUSTOMER", row.path("accountType").asText());
    assertEquals("user600", row.path("imAccountName").asText());
    assertFalse(row.path("imAccountDeleted").asBoolean());
    assertEquals("im_71", row.path("imAccount").asText());
    assertEquals("name-cipher", row.path("nameCipher").asText());
    assertEquals("phone-cipher", row.path("phoneCipher").asText());
    assertEquals("digest", row.path("phoneDigest").asText());
    assertEquals(3L, row.path("levelId").asLong());
    assertEquals("ACTIVE", row.path("status").asText());
    assertEquals(0, row.path("version").asInt());
  }

  /** 建档 / IM 绑定：单对象响应的字段集与列表行完全一致（同一份视图）。 */
  @Test
  void singleMemberJsonUsesTheSameFieldSet() throws Exception {
    when(memberService.create(2001L, 9001L, "张三", "13800001111", true, "im_71", "小明"))
        .thenReturn(display());

    JsonNode node = objectMapper.readTree(
        objectMapper.writeValueAsString(controller.create(new MemberController.CreateMemberRequest(
            9001L, "张三", "13800001111", true, "im_71", "小明"))));

    assertEquals(MEMBER_ID, node.path("id").asLong());
    assertEquals("M7", node.path("memberNo").asText());
    assertTrue(node.has("nameCipher"));
    assertTrue(node.has("accountType"));
    assertTrue(node.has("imAccountDeleted"));
  }

  /**
   * {@code GET /business/members/me} 的外形不变：{@code memberId} + {@code memberNo} + 钱包对象 + 积分对象。
   * 钱包/积分的字段契约由 {@code WalletTokenResponseContractTest} 覆盖，这里只保证这条端点的外层键名。
   */
  @Test
  void memberMeResponseKeepsItsOuterShape() throws Exception {
    when(memberService.getOrCreateByAccount(2001L, 1L)).thenReturn(display());
    when(walletService.balance(MEMBER_ID)).thenReturn(WalletAccount.zeroFor(MEMBER_ID, 1L, "USD"));
    when(pointService.ensureAccount(MEMBER_ID))
        .thenReturn(new PointAccount(1L, TENANT_ID, MEMBER_ID, 1L, 0L, 0L, 0));

    JsonNode node = objectMapper.readTree(
        objectMapper.writeValueAsString(controller.me()));

    assertEquals(MEMBER_ID, node.path("memberId").asLong());
    assertEquals("M7", node.path("memberNo").asText());
    assertTrue(node.has("wallet"));
    assertTrue(node.has("points"));
    // 储值账户懒初始化：没有账户时是零额只读视图（accountOpened=false），不落库
    assertFalse(node.path("wallet").path("accountOpened").asBoolean());
  }

  /** 储值管理列表行的字段名不变（{@code availableAmount}/{@code tokenAmount}/{@code currencyCode}/…）。 */
  @Test
  void memberWalletRowJsonKeepsItsFieldNames() throws Exception {
    Page<MemberDisplay> page = new Page<>(1, 20, 1L);
    page.setRecords(List.of(display()));
    when(memberService.list(1L, 20L, null, null, null, TimeRange.none())).thenReturn(page);
    when(walletService.balances(List.of(MEMBER_ID))).thenReturn(Map.of());

    Page<MemberController.MemberWalletRow> rows = controller.wallets(1L, 20L, null, null, null);
    JsonNode row = objectMapper.readTree(objectMapper.writeValueAsString(rows))
        .path("records").get(0);

    for (String field : List.of("memberId", "memberNo", "name", "phone", "availableAmount",
        "frozenAmount", "tokenAmount", "currencyCode", "accountOpened")) {
      assertTrue(row.has(field), "储值管理列表行缺少既有字段: " + field);
    }
    assertEquals(0L, row.path("availableAmount").asLong());
    assertEquals("0", row.path("tokenAmount").asText());
    assertFalse(row.path("accountOpened").asBoolean());
  }

  private static MemberDisplay display() {
    Member member = new Member(MEMBER_ID, TENANT_ID, 7L, 9001L, "im_71", "小明", JOINED_AT, "M7",
        "name-cipher", "phone-cipher", "digest", 3L, Member.STATUS_ACTIVE, JOINED_AT, null, 0);
    return MemberDisplay.withoutDisplay(member)
        .withDisplay("张三", "138****1111", "CUSTOMER", "user600", false);
  }
}
