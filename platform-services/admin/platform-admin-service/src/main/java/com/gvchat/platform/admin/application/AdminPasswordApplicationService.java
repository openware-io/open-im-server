package com.gvchat.platform.admin.application;

import com.gvchat.common.exception.ApiException;
import com.gvchat.platform.admin.domain.model.SaaAdminAccount;
import com.gvchat.platform.admin.domain.port.SaaAdminAccountRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/** SaaS 后台账号自助修改密码。仅更新 SaaS 管理账号凭据，不触碰 IM/IAM 授权。 */
@Service
public class AdminPasswordApplicationService {
    private final SaaAdminAccountRepository accounts;
    private final PasswordEncoder passwords;

    public AdminPasswordApplicationService(SaaAdminAccountRepository accounts, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.passwords = passwords;
    }

    public void changePassword(Long accountId, String currentPassword, String newPassword) {
        if (accountId == null) throw new ApiException(401, "ADMIN_SESSION_MISSING", "缺少平台账号会话");
        SaaAdminAccount account = accounts.findById(accountId)
                .filter(SaaAdminAccount::active)
                .orElseThrow(() -> new ApiException(401, "ADMIN_SESSION_INVALID", "平台账号会话已失效"));
        if (currentPassword == null || account.passwordHash() == null || !passwords.matches(currentPassword, account.passwordHash())) {
            throw new ApiException(400, "ADMIN_CURRENT_PASSWORD_INVALID", "当前密码不正确");
        }
        validate(newPassword);
        if (passwords.matches(newPassword, account.passwordHash())) {
            throw new ApiException(400, "ADMIN_PASSWORD_SAME", "新密码不能与当前密码相同");
        }
        if (accounts.updatePassword(accountId, passwords.encode(newPassword)) != 1) {
            throw new ApiException(409, "ADMIN_PASSWORD_UPDATE_FAILED", "密码更新失败，请重试");
        }
    }

    private static void validate(String password) {
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(400, "ADMIN_PASSWORD_INVALID", "密码至少 8 位，且不超过 72 字节");
        }
    }
}
