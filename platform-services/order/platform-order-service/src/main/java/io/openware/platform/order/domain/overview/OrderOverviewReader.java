package io.openware.platform.order.domain.overview;

import java.util.List;

/** Order 汇总查询端口；实现由基础设施层提供。 */
public interface OrderOverviewReader {
    List<OrderOverviewRow> read(OrderOverviewQuery query);
}
