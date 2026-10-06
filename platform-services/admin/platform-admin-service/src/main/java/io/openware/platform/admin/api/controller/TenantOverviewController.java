package io.openware.platform.admin.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.infrastructure.currency.CurrencyContextHolder;
import io.openware.platform.admin.infra.CustomerOverviewDomainClient;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import io.openware.platform.admin.infra.PaymentOverviewDomainClient;
import io.openware.platform.admin.infra.TenantOverviewDomainClient;
import io.openware.platform.admin.infra.OrderOverviewDomainClient;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 总部只读经营总览 BFF；Customer 只是第一个领域分项，后续按同一契约追加其他领域。 */
@RestController
@RequestMapping("/admin/tenant/overview")
public class TenantOverviewController {
    private final CustomerOverviewDomainClient customerClient;
    private final TenantIamDomainClient tenantIamClient;
    private final PaymentOverviewDomainClient paymentClient;
    private final TenantOverviewDomainClient tenantOverviewClient;
    private final OrderOverviewDomainClient orderOverviewClient;

    public TenantOverviewController(CustomerOverviewDomainClient customerClient, TenantIamDomainClient tenantIamClient) {
        this(customerClient, tenantIamClient, null, null, null);
    }

    public TenantOverviewController(CustomerOverviewDomainClient customerClient, TenantIamDomainClient tenantIamClient,
                                    PaymentOverviewDomainClient paymentClient) {
        this(customerClient, tenantIamClient, paymentClient, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TenantOverviewController(CustomerOverviewDomainClient customerClient, TenantIamDomainClient tenantIamClient,
                                    PaymentOverviewDomainClient paymentClient,
                                    TenantOverviewDomainClient tenantOverviewClient,
                                    OrderOverviewDomainClient orderOverviewClient) {
        this.customerClient = customerClient;
        this.tenantIamClient = tenantIamClient;
        this.paymentClient = paymentClient;
        this.tenantOverviewClient = tenantOverviewClient;
        this.orderOverviewClient = orderOverviewClient;
    }

    @GetMapping
    public TenantOverviewResponse overview(@RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(required = false) String businessType,
                                           @RequestParam(required = false) String storeIds) {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0 || context.storeId() != null) {
            throw new ApiException(403, "TENANT_SCOPE_REQUIRED", "总部总览必须在租户上下文中查看");
        }
        if (context.permissions() == null || !context.permissions().contains("tenant.overview.view")) {
            throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: tenant.overview.view");
        }
        validateStoreIds(context, storeIds);
        Map<String, String> failures = new LinkedHashMap<>();
        Map<String, Object> tenant = tenantOverviewClient == null ? Map.of()
                : callDomain("tenant", () -> tenantOverviewClient.overview(storeIds, businessType), failures);
        Map<String, Object> customer = callDomain("customer", () -> customerClient.overview(from, to, storeIds), failures);
        Map<String, Object> order = orderOverviewClient == null ? Map.of()
                : callDomain("order", () -> orderOverviewClient.overview(from, to, businessType, storeIds), failures);
        Map<String, Object> payment = paymentClient == null ? Map.of()
                : callDomain("payment", () -> paymentClient.overview(from, to, storeIds), failures);
        return new TenantOverviewResponse("TENANT", CurrencyContextHolder.get().code(), tenant, customer, order, payment,
                java.time.Instant.now(), failures.isEmpty() ? "COMPLETE" : "PARTIAL", failures);
    }

    private static Map<String, Object> callDomain(String domain, Supplier<Map<String, Object>> call,
                                                  Map<String, String> failures) {
        try {
            Map<String, Object> result = call.get();
            return result == null ? Map.of() : result;
        } catch (ApiException exception) {
            if (exception.getStatus() < 500) {
                throw exception;
            }
            failures.put(domain, exception.getCode() == null ? "DOMAIN_QUERY_FAILED" : exception.getCode());
            return Map.of();
        } catch (RuntimeException exception) {
            failures.put(domain, "DOMAIN_QUERY_TIMEOUT");
            return Map.of();
        }
    }

    private void validateStoreIds(TenantContext context, String storeIds) {
        if (storeIds == null || storeIds.isBlank()) {
            return;
        }
        Set<Long> requested;
        try {
            requested = Arrays.stream(storeIds.split(","))
                    .map(String::trim).filter(value -> !value.isBlank()).map(Long::valueOf).collect(Collectors.toSet());
        } catch (RuntimeException exception) {
            throw new ApiException(400, "STORE_FILTER_INVALID", "门店筛选非法");
        }
        Set<Long> allowed = tenantIamClient.staffStores(context).stream()
                .map(TenantIamDomainClient.StaffStore::id).collect(Collectors.toSet());
        if (!allowed.containsAll(requested)) {
            throw new ApiException(403, "STORE_SCOPE_FORBIDDEN", "门店不在当前租户作用域内");
        }
    }

    public record TenantOverviewResponse(String scope, String currencyCode, Map<String, Object> tenant,
                                         Map<String, Object> customer, Map<String, Object> order,
                                         Map<String, Object> payment,
                                         java.time.Instant updatedAt, String dataStatus,
                                         Map<String, String> failures) {
        public TenantOverviewResponse {
            failures = failures == null ? Map.of() : Map.copyOf(failures);
        }
    }
}
