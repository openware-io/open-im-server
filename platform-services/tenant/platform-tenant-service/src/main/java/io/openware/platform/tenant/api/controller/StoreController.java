package io.openware.platform.tenant.api.controller;

import io.openware.platform.tenant.api.converter.StoreConverter;
import io.openware.platform.tenant.api.dto.StoreView;
import io.openware.platform.tenant.application.StoreApplicationService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 门店列表与门店配置修改（ADM，租户后台）。
 * 租户边界由 TenantLineInnerInterceptor 自动附加 tenant_id 过滤（tnt_store 非忽略表）。
 *
 * <p>读写契约：
 * <ul>
 *   <li>{@code GET  /admin/tenant/stores?status=} → {@code StoreView[]}，含 {@code timezone} 与
 *       {@code businessDayCutoff}（多时区展示与编辑的真源，字段名与
 *       `docs/renovation/MULTI_TIMEZONE_DESIGN.md` 一致）；</li>
 *   <li>{@code PUT  /admin/tenant/stores/{id}} body {@code {timezone?, businessDayCutoff?, businessType?}}
 *       → 变更后的门店视图。</li>
 * </ul>
 *
 * <p>权限、租户/门店边界、校验、落库、审计与快照失效都在
 * {@link StoreApplicationService}；本类只做协议转换。
 */
@RestController
@RequestMapping("/admin/tenant/stores")
public class StoreController {

    private final StoreApplicationService storeService;

    public StoreController(StoreApplicationService storeService) {
        this.storeService = storeService;
    }

    /** 当前租户门店列表（可选状态过滤）。 */
    @GetMapping
    public List<StoreView> list(@RequestParam(required = false) String status) {
        return storeService.list(status).stream().map(StoreConverter::toView).toList();
    }

    /** 修改门店 Logo；空值恢复默认图。 */
    @PutMapping("/{id}/logo")
    public StoreView updateLogo(@PathVariable Long id, @RequestBody(required = false) LogoRequest request) {
        return StoreConverter.toView(storeService.updateLogoWithAudit(id,
                request == null ? null : request.logoUrl()));
    }

    /** 修改门店时区 / 营业日切点 / 业态（权限 + 门店/租户边界 + 校验 + 审计）。 */
    @PutMapping("/{id}")
    public StoreView update(@PathVariable Long id, @RequestBody(required = false) UpdateStoreRequest request) {
        return StoreConverter.toView(storeService.updateWithAudit(id,
                request == null ? null : request.timezone(),
                request == null ? null : request.businessDayCutoff(),
                request == null ? null : request.businessType()).store());
    }

    /**
     * 门店配置修改请求：字段都可选，至少传一个（都不传 → 400 {@code STORE_UPDATE_EMPTY}）。
     *
     * @param timezone           IANA 时区 id，例如 {@code Asia/Bangkok}
     * @param businessDayCutoff  营业日切点，{@code HH:mm} 或 {@code HH:mm:ss}，取值 {@code 00:00}–{@code 12:00}
     * @param businessType       ACTIVE 业态编码，例如 {@code KTV}
     */
    public record LogoRequest(String logoUrl) {
    }

    public record UpdateStoreRequest(String timezone, String businessDayCutoff, String businessType) {
        public UpdateStoreRequest(String timezone, String businessDayCutoff) {
            this(timezone, businessDayCutoff, null);
        }
    }
}
