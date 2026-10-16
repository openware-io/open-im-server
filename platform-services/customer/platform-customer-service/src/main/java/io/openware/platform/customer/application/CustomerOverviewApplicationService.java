package io.openware.platform.customer.application;

import io.openware.platform.customer.domain.member.repository.MemberRepository;
import io.openware.platform.customer.domain.point.model.PointAccount;
import io.openware.platform.customer.domain.point.repository.PointAccountRepository;
import io.openware.platform.customer.domain.wallet.model.WalletAccount;
import io.openware.platform.customer.domain.wallet.repository.WalletAccountRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Customer 总部实时汇总用例。
 *
 * <p><b>分层</b>：只依赖领域端口（member / point / wallet 三域），不再直接持有五个 Mapper。
 * 汇总是<b>只读</b>用例，用的是"列出并合计"与"期间净变动"两类读端口，没有聚合 SQL：
 * 合计口径（null 当 0、{@code CONSUME}/{@code REFUND} 记负）留在 Java 侧，
 * 与迁移前的算法逐条一致——迁移没有"顺手"改任何数值口径。
 *
 * <p>{@code storeIds} 只对<b>账本</b>的期间变动生效（余额合计是租户级的当前快照，
 * 与门店无关）：这与迁移前对 {@code cst_*_ledger.store_id} 施加筛选的范围完全相同。
 */
@Service
public class CustomerOverviewApplicationService {
    private final MemberRepository memberRepository;
    private final PointAccountRepository pointAccountRepository;
    private final WalletAccountRepository walletAccountRepository;

    public CustomerOverviewApplicationService(MemberRepository memberRepository,
                                              PointAccountRepository pointAccountRepository,
                                              WalletAccountRepository walletAccountRepository) {
        this.memberRepository = memberRepository;
        this.pointAccountRepository = pointAccountRepository;
        this.walletAccountRepository = walletAccountRepository;
    }

    public Summary summarize(LocalDateTime from, LocalDateTime to, String storeIds) {
        long memberCount = memberRepository.countAll();
        long pointsBalance = pointAccountRepository.listAll().stream()
                .mapToLong(PointAccount::available).sum();
        long walletBalance = walletAccountRepository.listAll().stream()
                .mapToLong(WalletAccount::available).sum();
        List<Long> stores = parseStoreIds(storeIds);
        long pointsDelta = pointAccountRepository.deltaForPeriodByStores(from, to, stores);
        long walletDelta = walletAccountRepository.deltaForPeriodByStores(from, to, stores);
        return new Summary(memberCount, pointsBalance, walletBalance, pointsDelta, walletDelta,
                Instant.now());
    }

    /**
     * 门店筛选解析：空/全空白 = 不筛（返回空列表，端口按"不限门店"处理）。
     *
     * <p>非法值原样抛出：控制器的 {@code validateStoreIds} 已在入口拦下（400 {@code STORE_FILTER_INVALID}），
     * 这里不重复造第二套校验，也不静默吞掉。
     */
    private static List<Long> parseStoreIds(String storeIds) {
        if (storeIds == null || storeIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(storeIds.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).map(Long::valueOf).toList();
    }

    public record Summary(long memberCount, long pointsBalance, long walletBalance, long pointsDelta,
                          long walletDelta, Instant updatedAt) {}
}
