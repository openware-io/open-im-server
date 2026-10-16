package io.openware.platform.customer.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.point.model.PointLedgerEntry;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository.LedgerPage;
import io.openware.platform.customer.infra.persistence.mapper.PointAccountMapper;
import io.openware.platform.customer.infra.persistence.mapper.PointLedgerMapper;
import io.openware.platform.customer.infra.persistence.po.CstPointAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstPointLedgerPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 积分仓储适配器的 SQL 形状与列保真（真实适配器 + mock mapper）。
 *
 * <p><b>为什么这个类必须存在</b>：按 standards/10 的测试分层，SQL 形状/列集合属于适配器职责，
 * 服务测试只断言端口调用语义。钱包切片迁移时把这类断言丢了且没有补上（记录在
 * {@code docs/renovation/CUSTOMER_LAYERING_HANDOFF.md} §5.1），结果是 CAS 的 SQL 形状没有任何测试锁定，
 * 后来真的写进了一个不存在的列（{@code @TableField(exist = false)}），只有集成测试才炸。
 * 本类把从服务测试迁出的 SQL 断言接住，并加列保真断言，不让同一个缺口出现第二次。
 *
 * <p>断言方式：捕获传给 mapper 的 {@link Wrapper}，用 MyBatis-Plus 自己的
 * {@code getTargetSql()} + {@code getSqlSegment()} 求值（不手写 SQL 字符串），
 * 因此"条件在不在、set 了哪些列"是照 MyBatis-Plus 的真实生成结果断的。
 */
class PointAccountRepositoryAdapterTest {

  private static final long ACCOUNT_ID = 11L;

  /**
   * 注册两张表的 {@code TableInfo}。
   *
   * <p>没有这一步，lambda 的列名解析在单元测试里是<b>空操作</b>：{@code eq}/{@code set} 只登记方法引用，
   * 不解析列名，于是 {@code getParamNameValuePairs()} 为空、{@code getSqlSet()} 里没有列。
   * 真跑时由 MyBatis-Plus 自动装配完成注册，所以这个坑只在"真适配器 + mock mapper"的测试里出现——
   * 表现出来是断言莫名其妙地空，而不是报错。用生产同一个 {@link TableInfoHelper} 注册，
   * 断言的就是真实映射下的列名。
   */
  @BeforeAll
  static void registerTableInfo() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
    assistant.setCurrentNamespace(PointAccountRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, CstPointAccountPo.class);
    TableInfoHelper.initTableInfo(assistant, CstPointLedgerPo.class);
  }

  private final PointAccountMapper accountMapper = mock(PointAccountMapper.class);
  private final PointLedgerMapper ledgerMapper = mock(PointLedgerMapper.class);
  private final PointAccountRepositoryAdapter adapter =
      new PointAccountRepositoryAdapter(accountMapper, ledgerMapper);

  // ------------------------------------------------------------------ CAS

  /**
   * CAS 的 SQL 形状：WHERE id = ? AND version = ?，SET 里必须有 available_points，
   * 且版本自增写在 SQL 里（{@code version = version + 1}），不是 Java 侧读出来加一。
   */
  @Test
  void updateBalanceWithVersionIsAConditionalUpdateWithSqlSideIncrement() {
    when(accountMapper.update(any(), any())).thenReturn(1);

    boolean updated = adapter.updateBalanceWithVersion(account(900L, 3), 3);

    assertTrue(updated);
    LambdaUpdateWrapper<CstPointAccountPo> wrapper = capturedUpdate();
    String where = evaluatedSql(wrapper);
    // WHERE 片段里的占位符已经还原成 `?`：丢掉 `version = ?` 的那次改动会让这条断言直接失败。
    assertTrue(where.contains("ID = ?"), "CAS 必须按 id 定位，实际: " + where);
    assertTrue(where.contains("VERSION = ?"), "CAS 必须带 version 条件，实际: " + where);
    // 条件参数值：期望版本必须原样进入条件（不是读出来 +1 的那个值）。
    Map<String, Object> params = wrapper.getParamNameValuePairs();
    assertTrue(containsNumber(params, ACCOUNT_ID), "CAS 必须按账户 id 定位: " + params.values());
    assertTrue(containsNumber(params, 3), "CAS 必须带读出来的期望版本作为条件: " + params.values());
    assertTrue(containsNumber(params, 900), "CAS 必须写入候选余额: " + params.values());
    assertTrue(wrapper.getSqlSet().replace(" ", "").toUpperCase().contains("VERSION=VERSION+1"),
        "版本自增必须写在 SQL 里，避免覆盖并发期间的版本号，实际: " + wrapper.getSqlSet());
  }

  /**
   * <b>列保真</b>：CAS 只写真实列。
   *
   * <p>{@code cst_point_account} 的列只有 id / tenant_id / customer_id / program_id /
   * available_points / frozen_points / version + 四个审计列。对不存在的列生成引用会让
   * MyBatis-Plus 在运行时抛 {@code can not find lambda cache for this property}——
   * 编译期与 {@code test-compile} 都看不出来。这里逐个 set 分支求值，锁住列集合。
   */
  @Test
  void updateBalanceWithVersionWritesOnlyRealColumns() {
    when(accountMapper.update(any(), any())).thenReturn(1);

    adapter.updateBalanceWithVersion(account(900L, 3), 3);

    LambdaUpdateWrapper<CstPointAccountPo> wrapper = capturedUpdate();
    assertTrue(wrapper.getSqlSet().contains("available_points"),
        "必须写 available_points: " + wrapper.getSqlSet());
    assertTrue(wrapper.getSqlSet().contains("frozen_points"),
        "必须写 frozen_points: " + wrapper.getSqlSet());
    assertTrue(wrapper.getSqlSet().contains("updated_at"),
        "必须刷新 updated_at: " + wrapper.getSqlSet());
    // 未改动的列不得出现在 SET 里：写入范围越小，越不可能误伤并发写入。
    assertFalse(wrapper.getSqlSet().contains("tenant_id"), "CAS 不得改 tenant_id: " + wrapper.getSqlSet());
    assertFalse(wrapper.getSqlSet().contains("customer_id"), "CAS 不得改 customer_id: " + wrapper.getSqlSet());
    assertFalse(wrapper.getSqlSet().contains("program_id"), "CAS 不得改 program_id: " + wrapper.getSqlSet());
    // PO 上没有 @TableField(exist = false) 字段（逐字段核对过），因此不可能误引用展示字段；
    // 若将来加了展示字段，这些断言会立刻发现它们跑进了 SET。
    assertFalse(wrapper.getSqlSet().contains("created_by"), "CAS 不得改 created_by: " + wrapper.getSqlSet());
  }

  /** 受影响 0 行 = 期间已被他人写入，必须原样返回 false，适配器不得吞掉这个信号。 */
  @Test
  void updateBalanceWithVersionReturnsFalseWhenZeroRowsAffected() {
    when(accountMapper.update(any(), any())).thenReturn(0);

    assertFalse(adapter.updateBalanceWithVersion(account(900L, 3), 3));
  }

  /** 传入的期望版本必须原样成为 SQL 条件（服务侧"读出来的版本"不能被改写成别的值）。 */
  @Test
  void updateBalanceWithVersionUsesTheGivenExpectedVersion() {
    when(accountMapper.update(any(), any())).thenReturn(1);

    adapter.updateBalanceWithVersion(account(900L, 7), 7);

    Map<String, Object> params = evaluatedParams(capturedUpdate());
    assertTrue(containsNumber(params, 7), "期望版本必须原样进入条件: " + params.values());
    assertFalse(containsNumber(params, 8), "不得把版本 +1 当成条件（那是写回后的值）: " + params.values());
  }

  // -------------------------------------------------------------- 读取账户

  /** 定位口径：按 customer_id 取一行（积分账户不分门店）。 */
  @Test
  void findByCustomerQueriesByCustomerIdAndLimitsToOne() {
    CstPointAccountPo po = po(ACCOUNT_ID);
    po.setCustomerId(7L);
    when(accountMapper.selectList(any())).thenReturn(List.of(po));

    Optional<PointAccount> found = adapter.findByCustomer(7L);

    assertTrue(found.isPresent());
    assertEquals(ACCOUNT_ID, found.get().id());
    LambdaQueryWrapper<CstPointAccountPo> wrapper = capturedSelect();
    String sql = evaluatedSql(wrapper);
    assertTrue(containsNumber(wrapper.getParamNameValuePairs(), 7),
        "必须按 customer_id 定位: " + wrapper.getParamNameValuePairs().values());
    assertTrue(sql.contains("LIMIT 1"), "同一会员只取一行: " + sql);
  }

  /** 找不到账户回 {@code Optional.empty()}，由服务侧决定 404 还是懒创建。 */
  @Test
  void findByCustomerReturnsEmptyWhenNoRow() {
    when(accountMapper.selectList(any())).thenReturn(List.of());

    assertTrue(adapter.findByCustomer(7L).isEmpty());
  }

  // -------------------------------------------------------------- 新增账户

  /**
   * 新增账户：默认零额 + 版本 0，审计时间由适配器填（DDL 里 created_at / updated_at 是 NOT NULL）。
   *
   * <p>{@code program_id} 由领域模型给定（懒创建时是 1），适配器不猜。
   */
  @Test
  void insertDefaultsZeroBalancesVersionZeroAndTimestamps() {
    when(accountMapper.insert(any(CstPointAccountPo.class))).thenAnswer(invocation -> {
      CstPointAccountPo inserted = invocation.getArgument(0);
      inserted.setId(ACCOUNT_ID);
      return 1;
    });

    PointAccount saved = adapter.insert(PointAccount.open(7L));

    ArgumentCaptor<CstPointAccountPo> captor = ArgumentCaptor.forClass(CstPointAccountPo.class);
    verify(accountMapper).insert(captor.capture());
    CstPointAccountPo po = captor.getValue();
    assertEquals(7L, po.getCustomerId());
    assertEquals(PointAccount.DEFAULT_PROGRAM_ID, po.getProgramId());
    assertEquals(0L, po.getAvailablePoints());
    assertEquals(0L, po.getFrozenPoints());
    assertEquals(0, po.getVersion());
    assertNotNull(po.getCreatedAt(), "created_at 是 NOT NULL 列，适配器必须填");
    assertNotNull(po.getUpdatedAt(), "updated_at 是 NOT NULL 列，适配器必须填");
    // 落库后的聚合：主键回填、其余原样。
    assertEquals(ACCOUNT_ID, saved.id());
    assertEquals(0L, saved.available());
    assertEquals(0, saved.currentVersion());
  }

  // -------------------------------------------------------------- 追加流水

  /**
   * 追加流水：逐个断言落到了正确的列上。
   *
   * <p>{@code created_at} 与 {@code occurred_at} 都是 NOT NULL 真实列：前者随条目带下来
   * （响应要展示它），后者缺失时由适配器兜底。
   */
  @Test
  void appendLedgerWritesEveryRealColumn() {
    LocalDateTime occurred = LocalDateTime.now().minusMinutes(5);
    LocalDateTime created = LocalDateTime.now();
    PointLedgerEntry entry = new PointLedgerEntry(null, 100L, null, ACCOUNT_ID, "EARN", 120L, "USD",
        1320L, "ORDER", 8L, "earn-key-1", "{\"earnRate\":1}", occurred.plusDays(30), occurred, created);

    adapter.appendLedger(entry);

    ArgumentCaptor<CstPointLedgerPo> captor = ArgumentCaptor.forClass(CstPointLedgerPo.class);
    verify(ledgerMapper).insert(captor.capture());
    CstPointLedgerPo po = captor.getValue();
    assertEquals(100L, po.getTenantId());
    assertNull(po.getStoreId());
    assertEquals(ACCOUNT_ID, po.getAccountId());
    assertEquals("EARN", po.getEntryType());
    assertEquals(120L, po.getPoints());
    assertEquals("USD", po.getCurrencyCode());
    assertEquals(1320L, po.getBalanceAfter());
    assertEquals("ORDER", po.getBusinessType());
    assertEquals(8L, po.getBusinessId());
    assertEquals("earn-key-1", po.getIdempotencyKey());
    assertEquals("{\"earnRate\":1}", po.getRuleSnapshotJson());
    assertEquals(occurred.plusDays(30), po.getExpiresAt());
    assertEquals(occurred, po.getOccurredAt());
    assertEquals(created, po.getCreatedAt());
    // 适配器只写真实映射的列：cst_point_ledger 未映射 updated_by / updated_at
    // （PO 上根本没有这两个字段，DDL 侧由 DC-11 默认值兜底），createdBy 也不由适配器臆造。
    assertNull(po.getCreatedBy(), "适配器不得臆造 created_by（DDL 默认 0）");
  }

  /** 缺失 occurredAt/createdAt 时用当前时间兜底：两列都是 NOT NULL，不能留空。 */
  @Test
  void appendLedgerFillsMissingTimestamps() {
    PointLedgerEntry entry = new PointLedgerEntry(null, 100L, 5L, ACCOUNT_ID, "ADJUST", -50L, "CNY",
        950L, null, null, "adjust-1", null, null, null, null);

    adapter.appendLedger(entry);

    ArgumentCaptor<CstPointLedgerPo> captor = ArgumentCaptor.forClass(CstPointLedgerPo.class);
    verify(ledgerMapper).insert(captor.capture());
    assertNotNull(captor.getValue().getOccurredAt());
    assertNotNull(captor.getValue().getCreatedAt());
    assertNull(captor.getValue().getExpiresAt(), "不失效的流水 expires_at 必须为空");
  }

  // ---------------------------------------------------------------- 幂等

  /** 幂等预检按 idempotency_key 计数（唯一键 uk_cst_point_ledger_idem 由 Flyway 兜底）。 */
  @Test
  void ledgerExistsCountsByIdempotencyKey() {
    when(ledgerMapper.selectCount(any())).thenReturn(1L);

    assertTrue(adapter.ledgerExists("key-1"));

    LambdaQueryWrapper<CstPointLedgerPo> wrapper = capturedLedgerCount();
    evaluatedSql(wrapper);
    assertTrue(wrapper.getParamNameValuePairs().containsValue("key-1"),
        "必须按幂等键计数: " + wrapper.getParamNameValuePairs().values());
  }

  /** 空/空白幂等键不去查库（不存在"空键已存在"这回事）。 */
  @Test
  void ledgerExistsShortCircuitsOnBlankKey() {
    assertFalse(adapter.ledgerExists(null));
    assertFalse(adapter.ledgerExists("  "));
    verify(ledgerMapper, org.mockito.Mockito.never()).selectCount(any());
  }

  // ---------------------------------------------------------------- 分页

  /**
   * 分页：按 id 倒序 + 显式 LIMIT/OFFSET（端口不允许泄漏 {@code Page}/{@code IPage}），
   * 且 total 走的是同条件的计数查询。
   */
  @Test
  void listLedgerOrdersByIdDescAndAppliesLimitOffset() {
    CstPointLedgerPo first = ledgerPo(9L);
    when(ledgerMapper.selectList(any())).thenReturn(List.of(first));
    when(ledgerMapper.selectCount(any())).thenReturn(3L);

    LedgerPage page = adapter.listLedger(ACCOUNT_ID, null, 20L, 10L);

    assertEquals(1, page.entries().size());
    assertEquals(9L, page.entries().get(0).id());
    assertEquals(3L, page.total());
    LambdaQueryWrapper<CstPointLedgerPo> wrapper = capturedLedgerList();
    String sql = evaluatedSql(wrapper);
    assertTrue(sql.contains("ORDER BY ID DESC"), "流水必须按 id 倒序: " + sql);
    assertTrue(sql.contains("LIMIT 10 OFFSET 20"), "必须翻译成 LIMIT/OFFSET: " + sql);
    assertTrue(containsNumber(wrapper.getParamNameValuePairs(), ACCOUNT_ID),
        "必须按账户定位: " + wrapper.getParamNameValuePairs().values());
  }

  /** 门店过滤只在传入门店时生效（总部不传门店 = 租户全量）。 */
  @Test
  void listLedgerFiltersByStoreOnlyWhenGiven() {
    when(ledgerMapper.selectList(any())).thenReturn(List.of());
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    adapter.listLedger(ACCOUNT_ID, 44L, 0L, 20L);

    assertTrue(containsNumber(evaluatedParams(capturedLedgerList()), 44L),
        "传了门店必须过滤 store_id");
  }

  /** 负偏移/负上限被夹到 0，不生成非法 SQL。 */
  @Test
  void listLedgerClampsNegativePagingValues() {
    when(ledgerMapper.selectList(any())).thenReturn(List.of());
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    adapter.listLedger(ACCOUNT_ID, null, -5L, -1L);

    assertTrue(capturedLedgerList().getTargetSql().toUpperCase().contains("LIMIT 0 OFFSET 0"),
        "负分页参数必须夹到 0: " + capturedLedgerList().getTargetSql());
  }

  // ------------------------------------------------------- fixture / 捕获

  /**
   * 参数表里是否存在该数值（按数值比较）。
   *
   * <p>MyBatis-Plus 的包装器把条件参数按 Java 类型装箱存放，同一个 11 可能是 {@code Integer}
   * 也可能是 {@code Long}；{@code containsValue(11L)} 会因此假失败。这里只比数值。
   */
  private static boolean containsNumber(Map<String, Object> params, long expected) {
    return params.values().stream()
        .anyMatch(value -> value instanceof Number number && number.longValue() == expected);
  }

  /**
   * 求值后的 SQL 片段（占位符还原成 {@code ?}、列名解析成真实列）。
   *
   * <p><b>调用它是读条件参数的前置条件</b>：MyBatis-Plus 3.5.17 把 WHERE 的参数值<b>懒</b>加进参数表，
   * 由 {@code getTargetSql()} 触发；不先求值就 {@code getParamNameValuePairs()} 只会看到 SET 的值
   * （条件值缺失），表现为"条件参数断言莫名其妙地空"。这条注释是为了让下一个人不用再查一遍。
   */
  private static String evaluatedSql(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
    return wrapper.getTargetSql().replaceAll("\\s+", " ").trim().toUpperCase();
  }

  /** 求值后的参数表（先触发 SQL 求值，再读参数）。 */
  private static Map<String, Object> evaluatedParams(
      com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
    evaluatedSql(wrapper);
    return wrapper.getParamNameValuePairs();
  }

  private static PointAccount account(long available, int version) {
    return new PointAccount(ACCOUNT_ID, 100L, 7L, 1L, available, 0L, version);
  }

  private static CstPointAccountPo po(Long id) {
    CstPointAccountPo po = new CstPointAccountPo();
    po.setId(id);
    po.setTenantId(100L);
    po.setProgramId(1L);
    po.setAvailablePoints(1000L);
    po.setFrozenPoints(0L);
    po.setVersion(3);
    return po;
  }

  private static CstPointLedgerPo ledgerPo(Long id) {
    CstPointLedgerPo po = new CstPointLedgerPo();
    po.setId(id);
    po.setTenantId(100L);
    po.setAccountId(ACCOUNT_ID);
    po.setEntryType("ADJUST");
    po.setPoints(1000L);
    po.setBalanceAfter(1000L);
    po.setCurrencyCode("USD");
    po.setIdempotencyKey("key-" + id);
    po.setOccurredAt(LocalDateTime.now());
    po.setCreatedAt(LocalDateTime.now());
    return po;
  }

  /**
   * 捕获 CAS 的 wrapper。
   *
   * <p>断言用 MyBatis-Plus <b>自己</b>生成 SQL 的两个求值口：{@code getSqlSegment()}（WHERE 条件）
   * 与 {@code getSqlSet()}（SET 列），而不是手写 SQL 字符串比对。这样断言的是"这条语句真的长什么样"，
   * 适配器换了写法但语义不变时不会误报，语义变了（丢了 version 条件、多 set 一列）一定报。
   */
  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<CstPointAccountPo> capturedUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<CstPointAccountPo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(accountMapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<CstPointAccountPo> capturedSelect() {
    ArgumentCaptor<LambdaQueryWrapper<CstPointAccountPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(accountMapper).selectList(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<CstPointLedgerPo> capturedLedgerList() {
    ArgumentCaptor<LambdaQueryWrapper<CstPointLedgerPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(ledgerMapper).selectList(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<CstPointLedgerPo> capturedLedgerCount() {
    ArgumentCaptor<LambdaQueryWrapper<CstPointLedgerPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(ledgerMapper).selectCount(captor.capture());
    return captor.getValue();
  }
}
