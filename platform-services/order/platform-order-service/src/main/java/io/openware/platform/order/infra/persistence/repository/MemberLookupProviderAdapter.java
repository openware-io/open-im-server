package io.openware.platform.order.infra.persistence.repository;

import io.openware.platform.order.domain.order.port.MemberLookupProvider;
import io.openware.platform.order.infra.persistence.mapper.CustomerLookupMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link MemberLookupProvider} 的持久化适配器：只读查询 {@code cst_member}。
 *
 * <p>跨上下文只读依赖的落点：order 域声明端口（它需要「当前账号是哪个会员」来收紧读路径），
 * 由本适配器用一个只读 SQL 适配 customer 域的表。适配器放在 {@code infra.persistence.repository}
 * 而不是别处，是为了让「控制器/应用层不得依赖持久化实现」这条边界检查
 * （{@code .infra.persistence.(mapper|po|repository).}）继续覆盖它。
 *
 * <p>租户或账号为空时直接返回空、不查库：调用方拿到空即按「解析不出会员」处理（403/404），
 * 不能把「上下文缺失」当成「查到了某个会员」。
 */
@Repository
public class MemberLookupProviderAdapter implements MemberLookupProvider {

    private final CustomerLookupMapper mapper;

    public MemberLookupProviderAdapter(CustomerLookupMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Long> findMemberId(Long tenantId, Long accountId) {
        if (tenantId == null || accountId == null) {
            return Optional.empty();
        }
        // 语句本身带 LIMIT 1 且按 (tenant_id, account_id) 定位（account_id 在租户间不保证唯一）。
        return Optional.ofNullable(mapper.findMemberId(tenantId, accountId));
    }
}
