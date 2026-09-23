package io.openware.common.payment.config;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import io.openware.infrastructure.tenant.TenantContextHolder;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;

public class PlatformTenantLineHandler implements TenantLineHandler {
    @Override
    public Expression getTenantId() {
        Long tenantId = TenantContextHolder.tenantIdOrNull();
        if (tenantId == null) throw new IllegalStateException("租户上下文缺失");
        return new LongValue(tenantId);
    }
    @Override
    public String getTenantIdColumn() { return "tenant_id"; }
    @Override
    public boolean ignoreTable(String t) {
        return t.startsWith("idt_") || t.equals("tnt_tenant") || t.equals("tnt_business_type") || t.equals("iam_permission") || t.endsWith("_event_outbox") || t.endsWith("_event_consumed");
    }
}
