package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.PermissionGuard;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.configuration.model.TenantConfig;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigReader;
import io.openware.platform.tenant.domain.configuration.repository.TenantConfigRepository;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 租户品牌配置：租户 Logo 的读取与替换。
 *
 * <p>租户 Logo 是租户级展示资源，存于 {@code tnt_tenant_config} 的
 * {@code tenant_logo_url} 键；图片本体由媒体服务上传，本服务只保存并校验 URL。
 * 空值表示恢复产品默认图。
 */
@Service
public class TenantBrandingApplicationService {

    public static final String CONFIG_KEY_LOGO_URL = "tenant_logo_url";

    private final TenantConfigReader reader;
    private final TenantConfigRepository repository;
    private final AuditClient auditClient;

    public TenantBrandingApplicationService(TenantConfigReader reader, TenantConfigRepository repository,
                                            AuditClient auditClient) {
        this.reader = reader;
        this.repository = repository;
        this.auditClient = auditClient;
    }

    public TenantBranding get() {
        Long tenantId = requireContext().tenantId();
        return new TenantBranding(reader.findValue(tenantId, 0L, CONFIG_KEY_LOGO_URL).orElse(""));
    }

    @Transactional
    public TenantBranding update(String logoUrl) {
        TenantContext context = requireContext();
        PermissionGuard.require("tenant.tenant.manage");
        String normalized = normalizeLogoUrl(logoUrl);
        repository.upsert(TenantConfig.tenantLevel(CONFIG_KEY_LOGO_URL, context.tenantId(), normalized));
        auditClient.recordAsync(AuditClient.AuditRecord.builder()
                .tenantId(context.tenantId())
                .action("tenant.branding.logo.update")
                .resourceType("tnt_tenant_config")
                .resourceId(String.valueOf(context.tenantId()))
                .detailJson("{\"logoUrl\":\"" + normalized + "\"}")
                .build());
        return new TenantBranding(normalized);
    }

    private static TenantContext requireContext() {
        TenantContext context = TenantContextHolder.get();
        if (context == null || context.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        return context;
    }

    private static String normalizeLogoUrl(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim();
        if (normalized.length() > 512) {
            throw new ApiException(400, "TENANT_LOGO_INVALID", "租户 Logo URL 不能超过 512 个字符");
        }
        if (normalized.startsWith("//") || normalized.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
            throw new ApiException(400, "TENANT_LOGO_INVALID", "租户 Logo URL 非法");
        }
        if (!normalized.startsWith("/") && !normalized.startsWith("https://")) {
            throw new ApiException(400, "TENANT_LOGO_INVALID", "租户 Logo 必须是上传后的相对地址或 HTTPS 地址");
        }
        return normalized;
    }

    public record TenantBranding(String logoUrl) {
    }
}
