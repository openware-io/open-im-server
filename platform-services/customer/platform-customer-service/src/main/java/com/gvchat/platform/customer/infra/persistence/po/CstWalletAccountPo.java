package com.gvchat.platform.customer.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * A380币储值账本（cst_wallet_account），同主体同币种唯一。
 * 金额使用最小货币单位整数（bigint）。
 */
@Getter
@Setter
@TableName("cst_wallet_account")
public class CstWalletAccountPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long customerId;
    private Long legalEntityId;
    private String currencyCode;
    private Long availableAmount;
    private Long frozenAmount;
    /**
     * 非持久化：可用余额对应的<b>代币数量</b>（字符串整数，展示口径）。
     * 公式 {@code 可用余额最小单位 ÷ 100 × 租户 ratio}，只用于界面展示数量；不得带货币符号，
     * 也绝不参与入账/扣减/对账（入账始终以 {@link #availableAmount} 的最小货币单位为准）。
     */
    @TableField(exist = false)
    private String tokenAmount;
    /** 非持久化：代币品牌展示名（租户配置 wallet_brand_name，缺省 A380币）；是展示文案，不是币种。 */
    @TableField(exist = false)
    private String tokenBrandName;
    /**
     * 非持久化：该会员是否<b>已开立</b>储值账户（{@code id != null} 即已开立）。
     *
     * <p>储值账户懒初始化（只在首次充值时创建）：没有账户时 {@link #availableAmount} 为 0，
     * 本字段让界面能区分「未开立储值账户」与「已开立但余额为 0」，避免把「没开过账户」误报成余额 0 的账户。
     */
    @TableField(exist = false)
    private Boolean accountOpened;
    private String status;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
