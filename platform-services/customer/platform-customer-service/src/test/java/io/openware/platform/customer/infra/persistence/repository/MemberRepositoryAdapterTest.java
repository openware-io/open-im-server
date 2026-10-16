package io.openware.platform.customer.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.customer.domain.member.model.Member;
import io.openware.platform.customer.domain.member.model.MemberFilter;
import io.openware.platform.customer.domain.member.repository.MemberRepository.MemberIdPage;
import io.openware.platform.customer.infra.persistence.mapper.MemberMapper;
import io.openware.platform.customer.infra.persistence.po.CstMemberPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 客户档案仓储适配器的 SQL 形状与列保真（真实适配器 + mock mapper）。
 *
 * <p><b>为什么这个类必须存在</b>：{@code CstMemberPo} 上有五个
 * {@code @TableField(exist = false)} 的展示字段（{@code name} / {@code phone} / {@code accountType} /
 * {@code imAccountName} / {@code imAccountDeleted}）。它们不是列；对它们生成列引用会让 MyBatis-Plus 抛
 * {@code can not find lambda cache for this property}，而 {@code test-compile} 完全干净
 * （钱包切片上真实发生过，只有集成测试抓到）。本类把那个缺口堵在适配器这一层：下面的断言直接落在
 * 真实 Wrapper 的 {@code getTargetSql()} / {@code getSqlSet()} 上。
 *
 * <p>断言方式：注册 {@code TableInfo} 后捕获传给 mapper 的
 * {@link AbstractWrapper}，用 MyBatis-Plus 自己生成的 SQL 求值，不手写 SQL 比对。
 */
class MemberRepositoryAdapterTest {

  private static final long MEMBER_ID = 11L;
  private static final long TENANT_ID = 100L;

  /**
   * 注册 {@code TableInfo}。
   *
   * <p>没有这一步，lambda 的列名解析在单元测试里是<b>空操作</b>：{@code eq}/{@code set} 只登记方法引用、
   * 不解析列名，于是 {@code getParamNameValuePairs()} 为空、{@code getSqlSet()} 里没有列。
   * 表现为"断言莫名其妙地空"而不是报错——真跑时由 MyBatis-Plus 装配完成注册。
   */
  @BeforeAll
  static void registerTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    assistant.setCurrentNamespace(MemberRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, CstMemberPo.class);
  }

  private final MemberMapper memberMapper = mock(MemberMapper.class);
  private final MemberRepositoryAdapter adapter = new MemberRepositoryAdapter(memberMapper);

  // ------------------------------------------------------------ 分页与过滤

  /**
   * 关键词的四个 OR 分支必须拼进<b>同一个 OR 组</b>（这样分页与 total 才与过滤口径一致）：
   * 客户号 LIKE / IM 账号 LIKE / IM 用户名 LIKE / 手机号摘要等值 / 盲索引候选 id IN。
   */
  @Test
  void listIdsBuildsOneOrGroupForEveryKeywordChannel() {
    when(memberMapper.selectList(any())).thenReturn(List.of());
    when(memberMapper.selectCount(any())).thenReturn(0L);

    adapter.listIds(filter("13800001111", "digest-1", List.of(21L, 22L), 0L, 10L));

    String sql = evaluatedSql(capturedSelect());
    assertTrue(sql.contains("MEMBER_NO LIKE"), sql);
    assertTrue(sql.contains("IM_ACCOUNT LIKE"), sql);
    assertTrue(sql.contains("IM_USERNAME LIKE"), sql);
    assertTrue(sql.contains("PHONE_DIGEST ="), "纯数字关键词必须走手机号摘要精确匹配: " + sql);
    assertTrue(sql.contains("ID IN"), "盲索引候选集必须参与 id IN 过滤: " + sql);
    // 条件值按 Java 类型装箱，必须按数值比较
    Map<String, Object> params = evaluatedParams(capturedSelect());
    assertTrue(containsNumber(params, 21L) && containsNumber(params, 22L), "候选 id 必须原样进入条件: " + params.values());
    assertTrue(params.containsValue("digest-1"), "手机号摘要必须原样进入条件: " + params.values());
  }

  /** 空候选集只跳过该分支：不得拼出非法的 {@code IN ()}，也不得因此丢掉其余通道。 */
  @Test
  void emptyBlindIndexHitsSkipTheInBranch() {
    when(memberMapper.selectList(any())).thenReturn(List.of());
    when(memberMapper.selectCount(any())).thenReturn(0L);

    adapter.listIds(filter("zzzz", null, List.of(), 0L, 10L));

    String sql = evaluatedSql(capturedSelect());
    assertFalse(sql.contains("ID IN"), sql);
    assertTrue(sql.contains("MEMBER_NO LIKE"), sql);
  }

  /** 无关键词：不拼 OR 组（其余筛选照常）。 */
  @Test
  void blankKeywordDoesNotBuildAnyOrGroup() {
    when(memberMapper.selectList(any())).thenReturn(List.of());
    when(memberMapper.selectCount(any())).thenReturn(0L);

    adapter.listIds(filter("  ", null, List.of(), 0L, 10L));

    String sql = evaluatedSql(capturedSelect());
    assertFalse(sql.contains("MEMBER_NO LIKE"), sql);
    assertFalse(sql.contains(" IM_ACCOUNT"), sql);
  }

  /** 等级 / 状态 / 未关联 / 门店 / 建档时间闭区间都落在 WHERE 上，且分页由显式 LIMIT/OFFSET 表达。 */
  @Test
  void listIdsAppliesEveryFilterAndExplicitPaging() {
    when(memberMapper.selectList(any())).thenReturn(List.of());
    when(memberMapper.selectCount(any())).thenReturn(7L);

    LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0, 0);
    LocalDateTime to = LocalDateTime.of(2026, 9, 30, 23, 59, 59);
    MemberFilter filter = new MemberFilter(null, null, List.of(), 3L, "ACTIVE", true, 7L, from, to, 50L, 25L);

    MemberIdPage page = adapter.listIds(filter);

    assertEquals(7L, page.total());
    String sql = evaluatedSql(capturedSelect());
    assertTrue(sql.contains("LEVEL_ID ="), sql);
    assertTrue(sql.contains("STATUS ="), sql);
    assertTrue(sql.contains("IM_ACCOUNT IS NULL"), sql);
    assertTrue(sql.contains("ORIGIN_STORE_ID ="), sql);
    assertTrue(sql.contains("JOINED_AT >="), sql);
    assertTrue(sql.contains("JOINED_AT <="), sql);
    assertTrue(sql.contains("ORDER BY") && sql.contains("DESC"), "列表按 id 倒序: " + sql);
    assertTrue(sql.contains("LIMIT 25"), "上限必须落到 SQL: " + sql);
    assertTrue(sql.contains("OFFSET 50"), "偏移必须原样落到 SQL（不做页码换算）: " + sql);
    // 计数语句与过滤条件同源，但绝不能带 ORDER BY / LIMIT：count 里带 ORDER BY 在 H2 上直接
    // 报 "Column id must be in the GROUP BY list"（集成测试实测过），MySQL 上也毫无意义。
    String countSql = evaluatedSql(capturedCount());
    assertTrue(countSql.contains("LEVEL_ID ="), "计数必须用同一套过滤条件: " + countSql);
    assertFalse(countSql.contains("ORDER BY"), countSql);
    assertFalse(countSql.contains("LIMIT"), countSql);
  }

  /** 空筛选条件不得被拼成谓词（{@code null} 的等级/门店/时间两端都不参与）。 */
  @Test
  void listIdsSkipsAbsentFilters() {
    when(memberMapper.selectList(any())).thenReturn(List.of());
    when(memberMapper.selectCount(any())).thenReturn(0L);

    adapter.listIds(new MemberFilter(null, null, List.of(), null, "  ", false, null, null, null, 0L, 20L));

    String sql = evaluatedSql(capturedSelect());
    assertFalse(sql.contains("LEVEL_ID"), sql);
    assertFalse(sql.contains("STATUS"), sql);
    assertFalse(sql.contains("IM_ACCOUNT IS NULL"), sql);
    assertFalse(sql.contains("ORIGIN_STORE_ID"), sql);
    assertFalse(sql.contains("JOINED_AT"), sql);
  }

  // ------------------------------------------------------------ 单行读取

  /** 按账号取最早一条：按 id 升序 + LIMIT 2（多行时由适配器打 WARN 取第一条）。 */
  @Test
  void findEarliestByAccountIdOrdersByIdAscendingAndLimitsToTwo() {
    when(memberMapper.selectList(any())).thenReturn(List.of(po(MEMBER_ID)));

    Optional<Member> found = adapter.findEarliestByAccountId(9001L);

    assertTrue(found.isPresent());
    assertEquals(MEMBER_ID, found.get().id());
    String sql = evaluatedSql(capturedSelect());
    assertTrue(sql.contains("ACCOUNT_ID ="), sql);
    assertTrue(sql.contains("ORDER BY") && sql.contains("ASC"), sql);
    assertTrue(sql.contains("LIMIT 2"), sql);
  }

  /** 账号为空不查库（待认领客户的枚举路径上会传 null）。 */
  @Test
  void findEarliestByAccountIdShortCircuitsOnNull() {
    assertTrue(adapter.findEarliestByAccountId(null).isEmpty());
  }

  /** 按 IM 账号取客户：{@code im_account} 为 NULL 的行不参与。 */
  @Test
  void findEarliestByImAccountQueriesTheImColumn() {
    when(memberMapper.selectList(any())).thenReturn(List.of());

    assertTrue(adapter.findEarliestByImAccount("im_71").isEmpty());

    String sql = evaluatedSql(capturedSelect());
    assertTrue(sql.contains("IM_ACCOUNT ="), sql);
    assertTrue(sql.contains("LIMIT 2"), sql);
  }

  /** 空白 IM 账号不查库（不能变成"匹配 im_account = '' "）。 */
  @Test
  void findEarliestByImAccountShortCircuitsOnBlank() {
    assertTrue(adapter.findEarliestByImAccount("  ").isEmpty());
  }

  /** 按 id 取：null 不查库。 */
  @Test
  void findByIdShortCircuitsOnNullId() {
    assertTrue(adapter.findById(null).isEmpty());
  }

  /** 按 id 批量取：保持传入顺序，缺失的 id 跳过（分页记录的顺序由端口决定）。 */
  @Test
  void findAllByIdKeepsTheRequestedOrder() {
    when(memberMapper.selectList(any())).thenReturn(List.of(po(22L), po(11L)));

    List<Member> members = adapter.findAllById(List.of(11L, 22L, 33L));

    assertEquals(List.of(11L, 22L), members.stream().map(Member::id).toList());
    String sql = evaluatedSql(capturedSelect());
    assertTrue(sql.contains("ID IN"), sql);
  }

  @Test
  void findAllByIdShortCircuitsOnEmptyInput() {
    assertTrue(adapter.findAllById(List.of()).isEmpty());
  }

  @Test
  void countAllCountsWithoutConditions() {
    when(memberMapper.selectCount(any())).thenReturn(42L);

    assertEquals(42L, adapter.countAll());
    String sql = evaluatedSql(capturedCount());
    assertFalse(sql.contains("WHERE"), "租户全量计数不得带业务条件: " + sql);
  }

  // ------------------------------------------------------------ 写入

  /**
   * <b>列保真（本类最要紧的一条）</b>：新增只 set 真实列。
   *
   * <p>{@code cst_member} 的列只有 id / tenant_id / origin_store_id / account_id / im_account /
   * im_username / im_bound_at / member_no / name_cipher / phone_cipher / phone_digest / level_id /
   * status / joined_at / expires_at / version + 四个审计列。
   * {@code name} / {@code phone} / {@code accountType} / {@code imAccountName} / {@code imAccountDeleted}
   * <b>不是列</b>（PO 上是 {@code @TableField(exist = false)}，DDL 里也没有，所以它们根本不会出现在
   * INSERT 的列集合里；这里再断言实体上也没被赋值，双保险钉住"展示值不进持久化"）。
   */
  @Test
  void insertWritesOnlyRealColumnsAndNoDisplayFields() {
    when(memberMapper.insert(any(CstMemberPo.class))).thenAnswer(invocation -> {
      CstMemberPo po = invocation.getArgument(0);
      po.setId(MEMBER_ID);
      return 1;
    });

    adapter.insert(member(MEMBER_ID));

    ArgumentCaptor<CstMemberPo> captor = ArgumentCaptor.forClass(CstMemberPo.class);
    verify(memberMapper).insert(captor.capture());
    CstMemberPo po = captor.getValue();
    assertEquals(TENANT_ID, po.getTenantId());
    assertEquals("M11", po.getMemberNo());
    assertEquals("im_71", po.getImAccount());
    assertEquals(9001L, po.getAccountId());
    assertEquals(7L, po.getOriginStoreId());
    assertEquals(3L, po.getLevelId());
    assertEquals("ACTIVE", po.getStatus());
    assertEquals(0, po.getVersion(), "版本列在适配器兜底为 0");
    assertNull(po.getCreatedBy(), "审计人列不写（由 DDL 默认值兜底）");
    assertNull(po.getUpdatedBy(), "审计人列不写（由 DDL 默认值兜底）");
    // 展示字段：不是列，绝不进 INSERT。
    assertNull(po.getName(), "name 是 exist = false 的展示字段，不得进入持久化");
    assertNull(po.getPhone(), "phone 是 exist = false 的展示字段，不得进入持久化");
    assertNull(po.getAccountType(), "accountType 是 exist = false 的展示字段");
    assertNull(po.getImAccountName(), "imAccountName 是 exist = false 的展示字段");
    assertNull(po.getImAccountDeleted(), "imAccountDeleted 是 exist = false 的展示字段");
  }

  /** IM 绑定只写四列 + 可选姓名密文；姓名传 null = 本次不改姓名（不是"清空"）。 */
  @Test
  void updateImBindingWritesBindingColumnsOnly() {
    when(memberMapper.update(any(), any())).thenReturn(1);
    LocalDateTime boundAt = LocalDateTime.of(2026, 9, 1, 10, 0, 0);

    assertTrue(adapter.updateImBinding(MEMBER_ID, "im_71", "小明", boundAt, null));

    LambdaUpdateWrapper<CstMemberPo> wrapper = capturedUpdate();
    String set = wrapper.getSqlSet();
    assertTrue(set.contains("im_account"), set);
    assertTrue(set.contains("im_username"), set);
    assertTrue(set.contains("im_bound_at"), set);
    assertTrue(set.contains("updated_at"), set);
    assertFalse(set.contains("name_cipher"), "姓名未传时不得写入 name_cipher: " + set);
    // 展示字段不是列：写进去会在运行时炸。
    assertFalse(set.contains("name ="), set);
    assertFalse(set.contains("phone"), set);
    assertFalse(set.contains("account_type"), set);
    String where = evaluatedSql(wrapper);
    assertTrue(where.contains("ID ="), where);
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(containsNumber(params, MEMBER_ID), "按档案 id 定位: " + params.values());
  }

  /** 一起改姓名时写入 name_cipher（改姓名必须同步重建盲索引，服务侧负责）。 */
  @Test
  void updateImBindingWritesNameCipherWhenNameChanges() {
    when(memberMapper.update(any(), any())).thenReturn(1);

    adapter.updateImBinding(MEMBER_ID, "im_71", "小明", LocalDateTime.now(), "v1:new-cipher");

    String set = capturedUpdate().getSqlSet();
    assertTrue(set.contains("name_cipher"), set);
  }

  /** 受影响 0 行 = 目标行消失，必须原样返回 false，适配器不得吞掉这个信号。 */
  @Test
  void updateImBindingReturnsFalseWhenNothingMatched() {
    when(memberMapper.update(any(), any())).thenReturn(0);

    assertFalse(adapter.updateImBinding(MEMBER_ID, "im_71", "小明", LocalDateTime.now(), null));
  }

  /** 可改属性只写真实列，且"传 null 的字段不参与 SET"（不是写成 NULL）。 */
  @Test
  void updateAttributesSetsOnlyProvidedColumns() {
    when(memberMapper.update(any(), any())).thenReturn(1);

    adapter.updateAttributes(MEMBER_ID, LocalDateTime.of(2026, 9, 1, 10, 0, 0), null, "FROZEN", null);

    String set = capturedUpdate().getSqlSet();
    assertTrue(set.contains("joined_at"), set);
    assertTrue(set.contains("status"), set);
    assertFalse(set.contains("level_id"), "levelId 传 null = 不参与 SET: " + set);
    assertFalse(set.contains("account_id"), "accountId 传 null = 不参与 SET: " + set);
    assertTrue(set.contains("updated_at"), set);
    assertFalse(set.contains("name_cipher"), set);
  }

  @Test
  void deleteByIdDelegatesToTheMapper() {
    when(memberMapper.deleteById(MEMBER_ID)).thenReturn(1);

    assertTrue(adapter.deleteById(MEMBER_ID));
  }

  // ---------------------------------------------------------------- helpers

  private static MemberFilter filter(String keyword, String digest, List<Long> nameMatchIds,
                                     long offset, long limit) {
    return new MemberFilter(keyword, digest, nameMatchIds, null, null, false, null, null, null,
        offset, limit);
  }

  private static String evaluatedSql(AbstractWrapper<?, ?, ?> wrapper) {
    return wrapper.getTargetSql().replaceAll("\\s+", " ").trim().toUpperCase();
  }

  /** 求值后的参数表（先触发 SQL 求值，再读参数——{@code eq} 的值是惰性进参数表的）。 */
  private static Map<String, Object> evaluatedParams(AbstractWrapper<?, ?, ?> wrapper) {
    evaluatedSql(wrapper);
    return wrapper.getParamNameValuePairs();
  }

  /** 条件值按 Java 类型装箱（Integer vs Long），必须按数值比较。 */
  private static boolean containsNumber(Map<String, Object> params, long expected) {
    return params.values().stream()
        .anyMatch(value -> value instanceof Number number && number.longValue() == expected);
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<CstMemberPo> capturedSelect() {
    ArgumentCaptor<LambdaQueryWrapper<CstMemberPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(memberMapper).selectList(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<CstMemberPo> capturedCount() {
    ArgumentCaptor<LambdaQueryWrapper<CstMemberPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(memberMapper).selectCount(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<CstMemberPo> capturedUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<CstMemberPo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(memberMapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
    return captor.getValue();
  }

  private static Member member(Long id) {
    LocalDateTime now = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
    return new Member(id, TENANT_ID, 7L, 9001L, "im_71", "小明", now, "M11", "name-cipher",
        "phone-cipher", "digest", 3L, Member.STATUS_ACTIVE, now, null, 0);
  }

  private static CstMemberPo po(Long id) {
    CstMemberPo po = new CstMemberPo();
    po.setId(id);
    po.setTenantId(TENANT_ID);
    po.setAccountId(9001L);
    po.setMemberNo("M" + id);
    return po;
  }
}
