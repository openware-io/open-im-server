package io.openware.platform.tenant.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.platform.tenant.api.converter.TenantConverter;
import io.openware.platform.tenant.api.dto.TenantView;
import io.openware.platform.tenant.application.TenantApplicationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台租户管理端点。
 *
 * <p>列表筛选与创建（含平台审计、幂等）都在 {@link TenantApplicationService}；
 * 本类只做协议转换。
 */
@RestController
@RequestMapping("/admin/platform/tenants")
public class TenantController {
    private final TenantApplicationService tenantService;

    public TenantController(TenantApplicationService tenantService) {
        this.tenantService = tenantService;
    }

    /**
     * 平台运营：租户列表（支付方式授权等平台管理页下拉用）。
     *
     * <p>{@code from}/{@code to} 按**租户创建时间** {@code created_at} 的闭区间筛选（统一口径见
     * {@code TimeRangeParams}：日期形态的 from/to 分别收口到当天起点与当天末尾；为空 = 不筛；
     * {@code from > to} → 400 {@code TIME_RANGE_INVALID}）。下拉调用不传即不筛，行为不变。
     */
    @GetMapping
    public List<TenantView> list(@RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to) {
        return tenantService.list(from, to).stream().map(TenantConverter::toView).toList();
    }

    /** 平台运营创建租户（ADM-02）。幂等：tenantCode 已存在则返回既有租户，不重复落库。 */
    @PostMapping
    public TenantView create(@RequestBody CreateTenantRequest req) {
        if (req == null) {
            throw new ApiException(400, "TENANT_CREATE_INVALID", "租户创建参数不能为空");
        }
        return TenantConverter.toView(tenantService.create(
                req.tenantCode(), req.name(), req.defaultLocale(), req.defaultTimezone()));
    }

    public record CreateTenantRequest(String tenantCode, String name, String defaultLocale, String defaultTimezone) {}
}
