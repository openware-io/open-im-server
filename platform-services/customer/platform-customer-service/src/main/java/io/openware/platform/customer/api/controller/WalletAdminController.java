package io.openware.platform.customer.api.controller;

import io.openware.platform.customer.application.WalletApplicationService;
import io.openware.platform.customer.application.WalletTokenDisplayService;
import io.openware.platform.customer.infra.persistence.po.CstWalletAccountPo;
import org.springframework.web.bind.annotation.*;

/**
 * A380币储值充值/退还（对齐 SAAS_PLATFORM_05_API.md §8 / KTV §8 充值管理）。
 * 写接口要求 Idempotency-Key 请求头；账本唯一键 (tenant_id, idempotency_key) 兜底。
 * 头声明为 {@code required = false}，缺失时由应用层统一抛 400 IDEMPOTENCY_KEY_REQUIRED（中文统一体），
 * 避免 Spring 抛出 MissingRequestHeaderException 后漏出框架默认错误体。
 *
 * <p>响应除货币字段（availableAmount / frozenAmount / currencyCode）外，补充展示用
 * {@code tokenAmount}（代币数量，字符串整数）与 {@code tokenBrandName}（代币品牌展示名）；
 * 代币数量只用于展示，入账/扣减始终以最小货币单位为准。
 */
@RestController
@RequestMapping("/admin/wallets")
public class WalletAdminController {
    private final WalletApplicationService walletService;
    private final WalletTokenDisplayService walletTokenDisplayService;

    public WalletAdminController(WalletApplicationService walletService,
                                 WalletTokenDisplayService walletTokenDisplayService) {
        this.walletService = walletService;
        this.walletTokenDisplayService = walletTokenDisplayService;
    }

    /** 储值充值（账本 RECHARGE，幂等）。 */
    @PostMapping("/recharge")
    public CstWalletAccountPo recharge(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                       @RequestBody RechargeRequest req) {
        return walletTokenDisplayService.decorate(walletService.recharge(req.customerId(), req.amount(),
                req.currency(), req.paymentMethod(), req.referenceNo(), idempotencyKey));
    }

    /** 储值退还（账本 REFUND，审批占位）。 */
    @PostMapping("/refund")
    public CstWalletAccountPo refund(@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                     @RequestBody RefundRequest req) {
        return walletTokenDisplayService.decorate(
                walletService.refund(req.customerId(), req.amount(), req.reason(), idempotencyKey));
    }

    public record RechargeRequest(Long customerId, Long amount, String currency, String paymentMethod, String referenceNo) {}
    public record RefundRequest(Long customerId, Long amount, String reason) {}
}
