package io.openware.platform.order.application;

import io.openware.platform.order.domain.overview.OrderOverviewQuery;
import io.openware.platform.order.domain.overview.OrderOverviewReader;
import io.openware.platform.order.domain.overview.OrderOverviewRow;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** Order 域总部实时汇总用例；不向 API 层泄露持久化对象。 */
@Service
public class OrderOverviewApplicationService {
    private final OrderOverviewReader reader;

    public OrderOverviewApplicationService(OrderOverviewReader reader) {
        this.reader = reader;
    }

    public Summary summarize(OrderOverviewQuery query) {
        return new Summary(reader.read(query), Instant.now());
    }

    public record Summary(List<OrderOverviewRow> rows, Instant updatedAt) {
        public Summary {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }
}
