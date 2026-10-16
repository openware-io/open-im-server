package io.openware.platform.tenant.domain.currency.repository;

import io.openware.platform.tenant.domain.currency.model.WalletAccount;
import java.util.List;

/**
 * 钱包账户**币种保护**端口（tenant 服务拥有的跨上下文只读/受限写端口）。
 *
 * <p>按 standards/10“跨上下文读取”，只读的跨上下文查询定义**专用只读端口且端口归属调用方**；
 * 本端口正是这种形态：它只暴露币种切换联动所需的两个动作，不含余额增减、冻结等真正属于
 * 钱包上下文的操作——tenant 域拿不到改余额的能力。
 *
 * <p><b>跨租户</b>：实现必须按显式入参 {@code tenantId} 过滤并跳过租户拦截器
 * （{@code @InterceptorIgnore}）——币种切换在上下文收敛后执行，但同一事务里还要写其它租户级表，
 * 不能依赖拦截器隐式过滤（隐式过滤失败时表现为"改币种成功但一个钱包都没改"）。
 */
public interface WalletAccountCurrencyPort {

  /**
   * 列出某租户下**非零余额且币种与目标不同**的账户（§2.2.2 的阻断判据）。
   *
   * <p>"非零余额"的判定在 SQL 里完成（{@code available_amount <> 0 OR frozen_amount <> 0}），
   * 不把全租户账户拉进内存再筛：账户数随客户数增长，内存筛选会随规模退化。
   */
  List<WalletAccount> listNonZeroInOtherCurrency(Long tenantId, String targetCurrencyCode);

  /**
   * 判定"同主体同目标币种"的账户是否已存在。
   *
   * <p>存在则改写会撞唯一键，并造成事实上的跨币种合并（禁区），调用方据此跳过并留痕。
   *
   * <p>{@code legalEntityId} 为 {@code null} 时必须按 {@code IS NULL} 匹配——SQL 里 {@code = NULL}
   * 永远不成立，用等值比较会把"确实存在"误判成"不存在"，进而撞唯一键。
   */
  boolean existsForSubject(Long tenantId, Long customerId, Long legalEntityId, String targetCurrencyCode);

  /**
   * 把指定账户的币种改为目标币种（**金额数字不变**）。
   *
   * <p>只改币种列与更新时间：金额数字不变是刻意的——若同时折算金额，那正是 §2.2.2 禁止的
   * "静默按汇率改写真实资产"。
   */
  void changeCurrency(Long walletAccountId, String targetCurrencyCode);
}
