package io.openware.platform.customer.application;

import io.openware.platform.customer.infra.persistence.mapper.MemberMapper;
import io.openware.platform.customer.infra.persistence.mapper.PointAccountMapper;
import io.openware.platform.customer.infra.persistence.mapper.PointLedgerMapper;
import io.openware.platform.customer.infra.persistence.mapper.WalletAccountMapper;
import io.openware.platform.customer.infra.persistence.mapper.WalletLedgerMapper;
import io.openware.platform.customer.infra.persistence.po.CstPointAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstPointLedgerPo;
import io.openware.platform.customer.infra.persistence.po.CstWalletAccountPo;
import io.openware.platform.customer.infra.persistence.po.CstWalletLedgerPo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

/** Customer 总部实时汇总用例；持久化细节留在应用层与 Mapper 适配器边界内。 */
@Service
public class CustomerOverviewApplicationService {
    private final MemberMapper memberMapper;
    private final PointAccountMapper pointAccountMapper;
    private final PointLedgerMapper pointLedgerMapper;
    private final WalletAccountMapper walletAccountMapper;
    private final WalletLedgerMapper walletLedgerMapper;

    public CustomerOverviewApplicationService(MemberMapper memberMapper, PointAccountMapper pointAccountMapper,
                                              PointLedgerMapper pointLedgerMapper, WalletAccountMapper walletAccountMapper,
                                              WalletLedgerMapper walletLedgerMapper) {
        this.memberMapper = memberMapper;
        this.pointAccountMapper = pointAccountMapper;
        this.pointLedgerMapper = pointLedgerMapper;
        this.walletAccountMapper = walletAccountMapper;
        this.walletLedgerMapper = walletLedgerMapper;
    }

    public Summary summarize(LocalDateTime from, LocalDateTime to, String storeIds) {
        QueryWrapper<CstPointAccountPo> pointAccounts = new QueryWrapper<>();
        QueryWrapper<CstWalletAccountPo> walletAccounts = new QueryWrapper<>();
        QueryWrapper<CstPointLedgerPo> pointLedger = new QueryWrapper<>();
        QueryWrapper<CstWalletLedgerPo> walletLedger = new QueryWrapper<>();
        applyStoreFilter(pointLedger, storeIds, "store_id");
        applyStoreFilter(walletLedger, storeIds, "store_id");
        applyTime(pointLedger, from, to, "occurred_at");
        applyTime(walletLedger, from, to, "occurred_at");
        long memberCount = memberMapper.selectCount(new QueryWrapper<>());
        long pointsBalance = pointAccountMapper.selectList(pointAccounts).stream()
                .mapToLong(a -> a.getAvailablePoints() == null ? 0L : a.getAvailablePoints()).sum();
        long walletBalance = walletAccountMapper.selectList(walletAccounts).stream()
                .mapToLong(a -> a.getAvailableAmount() == null ? 0L : a.getAvailableAmount()).sum();
        long pointsDelta = pointLedgerMapper.selectList(pointLedger).stream()
                .mapToLong(l -> l.getPoints() == null ? 0L : l.getPoints()).sum();
        long walletDelta = walletLedgerMapper.selectList(walletLedger).stream()
                .mapToLong(l -> walletDelta(l.getEntryType(), l.getAmount())).sum();
        return new Summary(memberCount, pointsBalance, walletBalance, pointsDelta, walletDelta, Instant.now());
    }

    private static void applyTime(QueryWrapper<?> query, LocalDateTime from, LocalDateTime to, String column) {
        if (from != null) query.ge(column, from);
        if (to != null) query.le(column, to);
    }

    private static void applyStoreFilter(QueryWrapper<?> query, String storeIds, String column) {
        if (storeIds == null || storeIds.isBlank()) return;
        java.util.List<Long> ids = java.util.Arrays.stream(storeIds.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).map(Long::valueOf).toList();
        query.in(column, ids);
    }

    private static long walletDelta(String entryType, Long amount) {
        long value = amount == null ? 0L : amount;
        return "CONSUME".equals(entryType) || "REFUND".equals(entryType) ? -value : value;
    }

    public record Summary(long memberCount, long pointsBalance, long walletBalance, long pointsDelta,
                          long walletDelta, Instant updatedAt) {}
}
