package com.gvchat.platform.customer.application;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.platform.customer.infra.persistence.po.CstPointAccountPo;

/**
 * 会员积分视图：积分账户 + 流水分页（GET /business/members/{id}/points）。
 *
 * <p><b>数量口径</b>：账户的 {@code availablePoints} / {@code frozenPoints} 与流水的
 * {@code points} / {@code balanceAfter} 都是积分<b>个数</b>（1 积分 = 1 个，1:1 不换算）。
 * 积分不是货币也不是储值代币：本响应<b>不含币种</b>（流水用 {@link PointLedgerRow} 剥离
 * {@code currencyCode} 快照），也<b>不含 tokenAmount</b>（代币换算只适用于钱包，见
 * {@link WalletTokenDisplayService}）。
 */
public record MemberPointsView(CstPointAccountPo account, Page<PointLedgerRow> ledger) {}
