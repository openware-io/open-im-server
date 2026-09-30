package io.openware.platform.order.domain.overview;

import java.time.LocalDateTime;
import java.util.List;

/** 总部订单汇总查询条件；租户标识由已验签上下文提供。 */
public record OrderOverviewQuery(long tenantId, List<Long> storeIds, String businessType,
                                 LocalDateTime fromInclusive, LocalDateTime toInclusive) {
    public OrderOverviewQuery {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
    }
}
