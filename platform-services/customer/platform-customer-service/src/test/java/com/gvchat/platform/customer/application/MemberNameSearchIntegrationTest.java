package com.gvchat.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.time.TimeRangeParams.TimeRange;
import com.gvchat.platform.customer.infra.persistence.mapper.MemberMapper;
import com.gvchat.platform.customer.infra.persistence.mapper.MemberNameTokenMapper;
import com.gvchat.platform.customer.infra.persistence.po.CstMemberNameTokenPo;
import com.gvchat.platform.customer.infra.persistence.po.CstMemberPo;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 「按姓名真的能搜到」的端到端回归（真跑 H2 + 真 MyBatis + 真租户拦截器，仅 mock 审计客户端）。
 *
 * <p>覆盖：建档写盲索引 → 全名/部分名/单字 <b>包含</b>检索都能命中；无命中为空页；
 * 客户号包含 / 手机号精确 / IM 账号 LIKE 三条通道；分页 total 与过滤口径一致；
 * 同一 IM 账号不能生成第二条客户（应用层唯一性——DB 侧唯一索引在 ACK 数据上可能是非唯一的）。
 */
@SpringBootTest
@ActiveProfiles("test")
class MemberNameSearchIntegrationTest {

  private static final long TENANT_ID = 1001L;

  @MockitoBean
  private AuditClient auditClient;

  @Autowired
  private MemberApplicationService memberService;

  @Autowired
  private MemberMapper memberMapper;

  @Autowired
  private MemberNameTokenMapper nameTokenMapper;

  @BeforeEach
  void setUpTenantAndCleanData() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, 2001L, 0L, 0, List.of("member.pii.view")));
    // 同一 H2 实例被本类多个用例共享：先清干净，避免用例之间互相污染（delete 带空 wrapper，
    // 租户拦截器会补上 tenant_id 条件，只删本租户）。
    nameTokenMapper.delete(new QueryWrapper<>());
    memberMapper.delete(new QueryWrapper<>());
  }

  @AfterEach
  void tearDownTenant() {
    TenantContextHolder.clear();
  }

  @Test
  void createBuildsBlindIndexAndNameContainsSearchWorks() {
    CstMemberPo zhangSanFeng = memberService.create(null, "张三丰", "13800001111", false, null, null);
    CstMemberPo zhangSan = memberService.create(null, "张三", "13800002222", false, null, null);
    CstMemberPo liSi = memberService.create(null, "李四", "13900003333", false, "im_71", "小李");

    // 建档即写 token（姓名 + 客户号），且姓名 token 确实可查
    assertTrue(nameTokenMapper.selectCount(null) > 0);
    List<CstMemberNameTokenPo> zhangTokens = nameTokenMapper.selectList(
        new QueryWrapper<CstMemberNameTokenPo>().eq("member_id", zhangSanFeng.getId()));
    assertTrue(zhangTokens.stream().anyMatch(t -> t.getToken().equals(NameBlindIndex.sha256Hex("张三丰"))));

    // ① 姓名「包含」语义：全名 / 部分名 / 单字
    assertEquals(List.of(zhangSanFeng.getId()), ids("张三丰"));
    assertEquals(List.of(zhangSanFeng.getId()), ids("三丰"));
    assertEquals(sorted(zhangSan.getId(), zhangSanFeng.getId()), ids("张"));
    assertEquals(List.of(liSi.getId()), ids("李四"));

    // 分页 total 与过滤口径一致：命中 2 条就是 2，不是租户全部客户数（本租户有 3 条）
    Page<CstMemberPo> zhangPage = memberService.list(1, 10, "张", null, null, TimeRange.none());
    assertEquals(2L, zhangPage.getTotal());

    // 无命中 = 空页
    Page<CstMemberPo> none = memberService.list(1, 10, "王五", null, null, TimeRange.none());
    assertEquals(0L, none.getTotal());
    assertTrue(none.getRecords().isEmpty());

    // ② 客户号包含（客户号 token 与姓名的 token 在同一张表）
    assertEquals(List.of(liSi.getId()), ids(liSi.getMemberNo()));
    String memberNoTail = liSi.getMemberNo().substring(liSi.getMemberNo().length() - 6);
    assertTrue(ids(memberNoTail).contains(liSi.getId()), "客户号局部也要能搜到: " + memberNoTail);

    // ③ 手机号（纯数字）走 SHA-256 摘要精确匹配
    assertEquals(List.of(zhangSanFeng.getId()), ids("13800001111"));

    // ④ IM 账号 / IM 用户名 LIKE
    assertEquals(List.of(liSi.getId()), ids("im_71"));
    assertEquals(List.of(liSi.getId()), ids("小李"));
  }

  @Test
  void createAndBindAreIdempotentAndRejectImAccountOwnedByAnotherCustomer() {
    CstMemberPo first = memberService.create(null, "张三", "", false, "im_99", "小张");
    CstMemberPo li = memberService.create(null, "李四", "", false, null, null);

    // 同一 IM 账号再次建档：返回既有客户，不新建
    CstMemberPo again = memberService.create(null, "张三", "", false, "im_99", "小张");
    assertEquals(first.getId(), again.getId());
    assertEquals(2L, memberService.list(1, 10, null, null, null, TimeRange.none()).getTotal());

    // 绑定冲突：im_99 已属于 first → 409 IM_ACCOUNT_ALREADY_BOUND，消息带现有客户号
    ApiException conflict = assertThrows(ApiException.class,
        () -> memberService.bindIm(li.getId(), "im_99", "小李", null));
    assertEquals(409, conflict.getStatus());
    assertEquals("IM_ACCOUNT_ALREADY_BOUND", conflict.getCode());
    assertTrue(conflict.getMessage().contains(first.getMemberNo()), conflict.getMessage());

    // 换一个未被占用的 IM 账号可以正常绑定，并立刻可按 IM 账号搜到
    CstMemberPo bound = memberService.bindIm(li.getId(), "im_100", "小李", null);
    assertEquals("im_100", bound.getImAccount());
    assertEquals(List.of(li.getId()), ids("im_100"));
  }

  @Test
  void rebuildNameIndexBackfillsOnlyMembersMissingTokens() {
    CstMemberPo created = memberService.create(null, "赵六", "", false, null, null);
    // 模拟「迁移前的老客户」：删掉 token 行
    nameTokenMapper.delete(new QueryWrapper<CstMemberNameTokenPo>().eq("member_id", created.getId()));
    assertTrue(ids("赵六").isEmpty());

    NameIndexRebuildResult result = memberService.rebuildNameIndexForTenant(TENANT_ID);

    assertEquals(1, result.scanned());
    assertEquals(1, result.rebuilt());
    assertEquals(0, result.failed());
    assertEquals(0L, result.remaining());
    assertEquals(List.of(created.getId()), ids("赵六"));

    // 再跑一次：已经建好索引的客户不会被重复处理（幂等、可重入）
    NameIndexRebuildResult second = memberService.rebuildNameIndexForTenant(TENANT_ID);
    assertEquals(0, second.scanned());
    assertEquals(0, second.rebuilt());
  }

  private List<Long> ids(String keyword) {
    return memberService.list(1, 50, keyword, null, null, TimeRange.none()).getRecords().stream()
        .map(CstMemberPo::getId)
        .sorted()
        .toList();
  }

  private static List<Long> sorted(Long... values) {
    return Stream.of(values).sorted().toList();
  }
}
