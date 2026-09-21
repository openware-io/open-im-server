package com.gvchat.platform.identity.api.controller;

import com.gvchat.platform.identity.application.AccountApplicationService;
import com.gvchat.platform.identity.application.AccountApplicationService.LoginResult;
import com.gvchat.platform.identity.infra.persistence.po.IdentityAccountPo;
import org.springframework.web.bind.annotation.*;

/**
 * SaaS 平台账号（独立于 im-user-service）。
 * 注册（账号 + 登录标识）与登录；登录签发 JWT Access Token。
 */
@RestController
@RequestMapping("/identity")
public class AccountController {
    private final AccountApplicationService accountService;

    public AccountController(AccountApplicationService accountService) { this.accountService = accountService; }

    /** 注册 SaaS 账号（手机号/邮箱 + 密码）。 */
    @PostMapping("/accounts")
    public IdentityAccountPo register(@RequestBody RegisterRequest req) {
        return accountService.register(req.loginType(), req.loginIdentifier(), req.credential());
    }

    /** 登录（手机号/邮箱 + 密码），返回账号 ID + 签名 JWT Access Token。 */
    @PostMapping("/login")
    public LoginResult login(@RequestBody LoginRequest req) {
        return accountService.login(req.loginType(), req.loginIdentifier(), req.credential());
    }

    public record RegisterRequest(String loginType, String loginIdentifier, String credential) {}
    public record LoginRequest(String loginType, String loginIdentifier, String credential) {}
}
