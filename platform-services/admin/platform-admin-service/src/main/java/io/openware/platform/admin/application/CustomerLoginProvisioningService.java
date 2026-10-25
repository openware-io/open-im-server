package io.openware.platform.admin.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.admin.infra.IdentityDomainClient;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 租户后台开通客户时，同步创建 C 端 SaaS 登录账号。 */
@Service
public class CustomerLoginProvisioningService {
    private static final String C_APP_ID = "saas-c";
    private static final String C_SCOPE = "profile.basic";

    private final IdentityDomainClient identity;
    private final TenantIamDomainClient iam;

    public CustomerLoginProvisioningService(IdentityDomainClient identity, TenantIamDomainClient iam) {
        this.identity = identity;
        this.iam = iam;
    }

    @Transactional
    public long provision(String phone, String password) {
        TenantContext ctx = TenantContextHolder.get();
        if (ctx == null || ctx.tenantId() <= 0) {
            throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
        }
        var snapshot = iam.permissions(ctx.accountId(), ctx.tenantId(), ctx.organizationId(), ctx.storeId(), true);
        if (!snapshot.permissions().contains("member.manage")) {
            throw new ApiException(403, "PERMISSION_DENIED", "缺少权限: member.manage");
        }
        if (phone == null || phone.isBlank() || password == null || password.length() < 8) {
            throw new ApiException(400, "CUSTOMER_LOGIN_INVALID", "手机号和至少 8 位密码不能为空");
        }
        long accountId = identity.ensureAccount("PHONE", phone.trim(), "USER");
        identity.setPassword(accountId, "PHONE", phone.trim(), password);
        iam.ensureMembership(accountId, ctx.tenantId(), "CUSTOMER", null, "C" + accountId);
        iam.grantConsumerAuthorization(accountId, C_APP_ID, C_SCOPE);
        return accountId;
    }
}
