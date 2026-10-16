package io.openware.platform.customer.infra.persistence.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import io.openware.platform.customer.domain.wallet.model.WalletLedgerEntry;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository.LedgerPage;
import io.openware.platform.customer.infra.persistence.mapper.WalletAccountMapper;
import io.openware.platform.customer.infra.persistence.mapper.WalletLedgerMapper;
import io.openware.platform.customer.infra.persistence.po.CstWalletAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstWalletLedgerPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 储值仓储适配器的 SQL 形状与列保真（真实适配器 + mock mapper）。
 *
 * <p><b>为什么这个类必须存在</b>：钱包切片迁移时，原服务测试里的 SQL 形状/列集合断言随迁移消失且没有补上
 * （记录在 {@code docs/renovation/CUSTOMER_LAYERING_HANDOFF.md} §5.1）。后果不是理论上的——CAS 适配器
 * 随后对三个 {@code @TableField(exist = false)} 的展示字段（{@code tokenAmount}/{@code tokenBrandName}/
 * {@code accountOpened}）调了 {@code .set(...)}，MyBatis-Plus 抛
 * {@code can not find lambda cache for this property [tokenAmount]}，
 * <b>recharge / consume / refund / release 全部在运行时失败，而 {@code test-compile} 完全干净</b>。
 * 发现它的是集成测试，不是单测。本类把那个缺口补上：对不存在的列生成引用会让下面的断言直接失败。
 *
 * <p>断言方式：捕获传给 mapper 的 {@link com.baomidou.mybatisplus.core.conditions.AbstractWrapper}，
 * 用 MyBatis-Plus 自己生成的 SQL 求值（{@code getTargetSql()} / {@code getSqlSet()}），不手写 SQL 比对。
 */
class WalletAccountRepositoryAdapterTest {

  private static final long ACCOUNT_ID = 11L;

  /**
   * 注册两张表的 {@code TableInfo}。
   *
   * <p>没有这一步，lambda 的列名解析在单元测试里是<b>空操作</b>：{@code eq}/{@code set} 只登记方法引用、
   * 不解析列名，于是 {@code getParamNameValuePairs()} 为空、{@code getSqlSet()} 里没有列。
   * 表现为"断言莫名其妙地空"而不是报错——真跑时由 MyBatis-Plus 装配完成注册。
   */
  @BeforeAll
  static void registerTableInfo() {
    MybatisConfiguration configuration = new MybatisConfiguration();
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
    assistant.setCurrentNamespace(WalletAccountRepositoryAdapterTest.class.getName());
    TableInfoHelper.initTableInfo(assistant, CstWalletAccountPo.class);
    TableInfoHelper.initTableInfo(assistant, CstWalletLedgerPo.class);
  }

  private final WalletAccountMapper accountMapper = mock(WalletAccountMapper.class);
  private final WalletLedgerMapper ledgerMapper = mock(WalletLedgerMapper.class);
  private final WalletAccountRepositoryAdapter adapter =
      new WalletAccountRepositoryAdapter(accountMapper, ledgerMapper);

  // -------------------------------------------------------------------- CAS

  /**
   * 余额写入必须是<b>带 version 条件</b>的单条 UPDATE，且版本自增写在 SQL 里。
   *
   * <p>这正是原实现缺的那一步：{@code bump(account); accountMapper.updateById(account)} 维护了 version
   * 却从不判定，PO 上没有 {@code @Version}、工程里也没有 {@code OptimisticLockerInnerInterceptor}。
   * 丢掉 {@code .eq(version, ...)} 会让这条断言失败。
   */
  @Test
  void updateBalanceWithVersionIsAConditionalUpdateWithSqlSideIncrement() {
    when(accountMapper.update(any(), any())).thenReturn(1);

    assertTrue(adapter.updateBalanceWithVersion(account(900L, 3), 3));

    LambdaUpdateWrapper<CstWalletAccountPo> wrapper = capturedUpdate();
    String where = evaluatedSql(wrapper);
    assertTrue(where.contains("ID = ?"), "CAS 必须按 id 定位，实际: " + where);
    assertTrue(where.contains("VERSION = ?"), "CAS 必须带 version 条件，实际: " + where);
    assertTrue(wrapper.getSqlSet().replace(" ", "").toUpperCase().contains("VERSION=VERSION+1"),
        "版本自增必须写在 SQL 里，避免覆盖并发期间的版本号，实际: " + wrapper.getSqlSet());
  }

  /**
   * <b>列保真（本类最要紧的一条）</b>：CAS 只写真实列。
   *
   * <p>{@code cst_wallet_account} 的列只有 id / tenant_id / customer_id / legal_entity_id /
   * currency_code / available_amount / frozen_amount / status / version + 四个审计列。
   * {@code token_amount}、{@code token_brand_name}、{@code account_opened} <b>不是列</b>
   * （PO 上是 {@code @TableField(exist = false)}，DDL 里也没有）——对它们生成列引用会在运行时抛
   * {@code can not find lambda cache for this property}。下面第二条断言就是钉住这个已发生过的缺陷。
   */
  @Test
  void updateBalanceWithVersionWritesOnlyRealColumns() {
    when(accountMapper.update(any(), any())).thenReturn(1);

    adapter.updateBalanceWithVersion(account(900L, 3), 3);

    String set = capturedUpdate().getSqlSet();
    assertTrue(set.contains("available_amount"), "必须写 available_amount: " + set);
    assertTrue(set.contains("frozen_amount"), "必须写 frozen_amount: " + set);
    assertTrue(set.contains("updated_at"), "必须刷新 updated_at: " + set);
    // 展示字段不是列：写进去会在运行时炸（本项目真实发生过）。
    assertFalse(set.contains("token_amount"), "token_amount 不是列，不得进入 SET: " + set);
    assertFalse(set.contains("token_brand_name"), "token_brand_name 不是列，不得进入 SET: " + set);
    assertFalse(set.contains("account_opened"), "account_opened 不是列，不得进入 SET: " + set);
    // 定位列与审计列不得被改写。
    assertFalse(set.contains("tenant_id"), "CAS 不得改 tenant_id: " + set);
    assertFalse(set.contains("customer_id"), "CAS 不得改 customer_id: " + set);
    assertFalse(set.contains("legal_entity_id"), "CAS 不得改 legal_entity_id: " + set);
    assertFalse(set.contains("currency_code"), "CAS 不得改币种快照: " + set);
    assertFalse(set.contains("created_by"), "CAS 不得改 created_by: " + set);
  }

  /** 期望版本必须原样成为条件，不能是写回后的值。 */
  @Test
  void updateBalanceWithVersionUsesTheGivenExpectedVersion() {
    when(accountMapper.update(any(), any())).thenReturn(1);

    adapter.updateBalanceWithVersion(account(900L, 7), 7);

    Map<String, Object> params = evaluatedParams(capturedUpdate());
    assertTrue(containsNumber(params, ACCOUNT_ID), "CAS 必须按账户 id 定位: " + params.values());
    assertTrue(containsNumber(params, 7), "期望版本必须原样进入条件: " + params.values());
    assertTrue(containsNumber(params, 900), "CAS 必须写入候选余额: " + params.values());
    assertFalse(containsNumber(params, 8), "不得把版本 +1 当成条件（那是写回后的值）: " + params.values());
  }

  /** 受影响 0 行 = 期间已被他人写入，必须原样返回 false，适配器不得吞掉这个信号。 */
  @Test
  void updateBalanceWithVersionReturnsFalseWhenZeroRowsAffected() {
    when(accountMapper.update(any(), any())).thenReturn(0);

    assertFalse(adapter.updateBalanceWithVersion(account(900L, 3), 3));
  }

  // ------------------------------------------------------------------ 读取

  /** 充值路径的定位口径：按会员 + 币种取一行（同会员不同币种是各自独立的账户）。 */
  @Test
  void findByCustomerAndCurrencyQueriesBothColumnsAndLimitsToOne() {
    CstWalletAccountPo po = po(ACCOUNT_ID);
    when(accountMapper.selectList(any())).thenReturn(List.of(po));

    Optional<WalletAccount> found = adapter.findByCustomerAndCurrency(7L, "CNY");

    assertTrue(found.isPresent());
    assertEquals(ACCOUNT_ID, found.get().id());
    LambdaQueryWrapper<CstWalletAccountPo> wrapper = capturedSelect();
    Map<String, Object> params = evaluatedParams(wrapper);
    assertTrue(containsNumber(params, 7), "必须按 customer_id 定位: " + params.values());
    assertTrue(params.containsValue("CNY"), "必须按 currency_code 定位: " + params.values());
    assertTrue(evaluatedSql(wrapper).contains("LIMIT 1"), "同会员同币种只取一行");
  }

  /** 没有账户回 {@code Optional.empty()}，由服务侧决定是零额视图还是拒绝。 */
  @Test
  void findByCustomerReturnsEmptyWhenNoRow() {
    when(accountMapper.selectList(any())).thenReturn(List.of());

    assertTrue(adapter.findByCustomer(7L).isEmpty());
  }

  /** CAS 冲突后按 id 重读，用于重算。 */
  @Test
  void findByIdReturnsTheStoredAccount() {
    when(accountMapper.selectById(ACCOUNT_ID)).thenReturn(po(ACCOUNT_ID));

    assertTrue(adapter.findById(ACCOUNT_ID).isPresent());
  }

  /** 空 id 不查库（零额只读视图的 id 就是 null，不能把它当成一次查询）。 */
  @Test
  void findByIdShortCircuitsOnNullId() {
    assertTrue(adapter.findById(null).isEmpty());
  }

  // ------------------------------------------------------------------ 账本

  /**
   * 账本只追加，且列集合只来自真实列。
   *
   * <p>端口上<b>没有</b> update/delete 账本的方法，所以"只追加"是端口形状保证的结构属性；
   * 这里再钉住插入的列集合，避免写入不存在/不可写的列。
   */
  @Test
  void insertLedgerWritesTheRealColumns() {
    adapter.insertLedger(new WalletLedgerEntry(null, 100L, 5L, ACCOUNT_ID, "CONSUME", 300L, 700L,
        "CNY", null, null, 9L, null, "key-1", LocalDateTime.now()));

    ArgumentCaptor<CstWalletLedgerPo> captor = ArgumentCaptor.forClass(CstWalletLedgerPo.class);
    verify(ledgerMapper).insert(captor.capture());
    CstWalletLedgerPo po = captor.getValue();
    assertEquals(ACCOUNT_ID, po.getWalletAccountId());
    assertEquals("CONSUME", po.getEntryType());
    assertEquals(300L, po.getAmount());
    assertEquals(700L, po.getBalanceAfter());
    // 币种快照由服务侧传入并原样落库（账本自证币种，不依赖回查账户）。
    assertEquals("CNY", po.getCurrencyCode());
    assertEquals("key-1", po.getIdempotencyKey());
  }

  /** 流水按 id 倒序分页，LIMIT/OFFSET 由调用方给的偏移与上限翻译而来。 */
  @Test
  void listLedgerAppliesOrderAndPaging() {
    when(ledgerMapper.selectList(any())).thenReturn(List.of());
    when(ledgerMapper.selectCount(any())).thenReturn(0L);

    LedgerPage page = adapter.listLedger(ACCOUNT_ID, null, 20L, 10L);

    assertEquals(0L, page.total());
    String sql = evaluatedSql(capturedLedgerList());
    assertTrue(sql.contains("ORDER BY") && sql.contains("DESC"), "流水必须按 id 倒序: " + sql);
    assertTrue(sql.contains("LIMIT 10"), "上限必须落到 SQL: " + sql);
    assertTrue(sql.contains("OFFSET 20"), "偏移必须落到 SQL: " + sql);
  }

  /** 幂等键为空即视为"不存在"，不查库（避免空串匹配到别人的行）。 */
  @Test
  void ledgerExistsShortCircuitsOnBlankKey() {
    assertFalse(adapter.ledgerExists(null));
    assertFalse(adapter.ledgerExists("  "));
  }

  // ---------------------------------------------------------------- helpers

  private static String evaluatedSql(com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
    return wrapper.getTargetSql().replaceAll("\\s+", " ").trim().toUpperCase();
  }

  /** 求值后的参数表（先触发 SQL 求值，再读参数——{@code eq} 的值是惰性进参数表的）。 */
  private static Map<String, Object> evaluatedParams(
      com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> wrapper) {
    evaluatedSql(wrapper);
    return wrapper.getParamNameValuePairs();
  }

  /** 条件值按 Java 类型装箱（Integer vs Long），必须按数值比较。 */
  private static boolean containsNumber(Map<String, Object> params, long expected) {
    return params.values().stream()
        .anyMatch(value -> value instanceof Number number && number.longValue() == expected);
  }

  private static WalletAccount account(long available, int version) {
    return new WalletAccount(ACCOUNT_ID, 100L, 7L, 1L, "CNY", available, 0L, null, null, true,
        "ACTIVE", version);
  }

  private static CstWalletAccountPo po(Long id) {
    CstWalletAccountPo po = new CstWalletAccountPo();
    po.setId(id);
    po.setTenantId(100L);
    po.setCustomerId(7L);
    po.setLegalEntityId(1L);
    po.setCurrencyCode("CNY");
    po.setAvailableAmount(1000L);
    po.setFrozenAmount(0L);
    po.setStatus("ACTIVE");
    po.setVersion(3);
    return po;
  }

  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<CstWalletAccountPo> capturedUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<CstWalletAccountPo>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(accountMapper).update(org.mockito.ArgumentMatchers.isNull(), captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<CstWalletAccountPo> capturedSelect() {
    ArgumentCaptor<LambdaQueryWrapper<CstWalletAccountPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(accountMapper).selectList(captor.capture());
    return captor.getValue();
  }

  @SuppressWarnings("unchecked")
  private LambdaQueryWrapper<CstWalletLedgerPo> capturedLedgerList() {
    ArgumentCaptor<LambdaQueryWrapper<CstWalletLedgerPo>> captor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(ledgerMapper).selectList(captor.capture());
    return captor.getValue();
  }
}
