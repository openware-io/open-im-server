package io.openware.platform.order.infra.persistence.repository;

import io.openware.platform.order.domain.overview.OrderOverviewQuery;
import io.openware.platform.order.domain.overview.OrderOverviewReader;
import io.openware.platform.order.domain.overview.OrderOverviewRow;
import io.openware.platform.order.infra.persistence.mapper.OrderOverviewMapper;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Order 汇总查询端口的 MyBatis 适配器。 */
@Repository
public class OrderOverviewRepository implements OrderOverviewReader {
    private final OrderOverviewMapper mapper;

    public OrderOverviewRepository(OrderOverviewMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<OrderOverviewRow> read(OrderOverviewQuery query) {
        return mapper.selectOverview(query.tenantId(), query.storeIds(), query.businessType(),
                        query.fromInclusive(), query.toInclusive()).stream()
                .map(row -> new OrderOverviewRow(row.getStoreId(), row.getBusinessType(), row.getCurrencyCode(),
                        row.getOrderCount(), row.getRevenueAmount(), row.getPaidAmount()))
                .toList();
    }
}
