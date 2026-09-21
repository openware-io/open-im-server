package com.gvchat.platform.customer.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * A380币储值账本流水（cst_wallet_ledger），只追加，不更新历史流水。
 */
@Getter
@Setter
@TableName("cst_wallet_ledger")
public class CstWalletLedgerPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long walletAccountId;
    private String entryType;
    private Long amount;
    private Long balanceAfter;
    /** 币种快照（与钱包账户币种一致；账本自证币种，改租户设置不改历史流水）。 */
    private String currencyCode;
    /**
     * 非持久化：本笔发生额对应的<b>代币数量</b>（字符串整数，展示口径）。
     * 公式 {@code 发生额最小单位 ÷ 100 × 租户 ratio}，只用于界面展示数量；
     * 账本金额口径与对账仍只认 {@link #amount} / {@link #balanceAfter} 的最小货币单位整数。
     */
    @TableField(exist = false)
    private String tokenAmount;
    /** 非持久化：代币品牌展示名（租户配置 wallet_brand_name，缺省 A380币）；是展示文案，不是币种。 */
    @TableField(exist = false)
    private String tokenBrandName;
    private Long orderId;
    private Long fxQuoteId;
    private String idempotencyKey;
    private LocalDateTime occurredAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
