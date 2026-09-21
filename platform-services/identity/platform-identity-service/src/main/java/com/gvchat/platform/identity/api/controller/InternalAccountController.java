package com.gvchat.platform.identity.api.controller;

import com.gvchat.platform.identity.application.AccountApplicationService;
import com.gvchat.platform.identity.application.ImUnifiedAccountApplicationService;
import com.gvchat.platform.identity.infra.persistence.po.IdentityAccountPo;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 账号内部端点（供 platform-admin-service 运营人员开通、IM 后台删除用户调用，不走网关）。
 */
@RestController
@RequestMapping("/internal/accounts")
public class InternalAccountController {

    private final AccountApplicationService accountService;
    private final ImUnifiedAccountApplicationService imUnifiedAccountService;

    public InternalAccountController(AccountApplicationService accountService,
                                     ImUnifiedAccountApplicationService imUnifiedAccountService) {
        this.accountService = accountService;
        this.imUnifiedAccountService = imUnifiedAccountService;
    }

    /** 按登录标识查/建账号（IM 账号开通运营人员用）。 */
    @PostMapping("/ensure")
    public IdentityAccountPo ensure(@RequestBody EnsureAccountRequest req) {
        return accountService.ensureAccount(req.loginType(), req.loginIdentifier(), req.accountType());
    }

    /**
     * 按 IM 用户名（{@code im_<id>}）查统一账号落点：账号类型 + 登录标识/OAuth 绑定计数。
     *
     * <p>只读查询：供运维与审计核对「这个 IM 用户在统一账号模型里还有什么落点」。
     */
    @GetMapping("/im/{username}")
    public ImUnifiedAccountApplicationService.ImAccountView imAccount(@PathVariable String username) {
        return imUnifiedAccountService.findByImUsername(username);
    }

    /**
     * 清理某个 IM 用户名在统一账号模型里的落点（IM 后台删除用户的级联一步，幂等）。
     *
     * <p>由 IM 侧在**自己的事务内**调用；本端点**不做任何拒绝**：只清 IM 身份（登录标识 + OAuth 绑定），
     * 账号本体仅在「客户账号 + 孤儿」时才删，员工 / 平台运营账号本体保留（等后台换绑 IM 账号）。
     */
    @DeleteMapping("/im/{username}")
    public ImUnifiedAccountApplicationService.ImAccountPurgeResult purgeImAccount(@PathVariable String username) {
        return imUnifiedAccountService.purgeByImUsername(username);
    }

    public record EnsureAccountRequest(String loginType, String loginIdentifier, String accountType) {}
}
