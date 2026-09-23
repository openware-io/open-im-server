package io.openware.platform.tenant.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 会员储值账户（`cst_wallet_account`）的**币种联动视图**。
 *
 * <p>本 PO 只用于「租户切换币种」时的余额保护（`docs/standards/16_CURRENCY_CONVENTIONS.md` §2.2.2）：
 * 校验是否存在非零余额且币种不同的账户，并在显式 `migrateBalances` 时把币种改写为新币种（金额数字不变）。
 *
 * <p>为什么由 tenant-service 直接读这张表：全平台共用同一个 MySQL 库（各服务 datasource 均指向
 * `open_saas`），而币种切换必须与 `tnt_store.default_currency` 写穿在**同一事务**内完成，
 * 走跨服务内部调用无法保证事务一致性。字段只声明联动所需的列，账户其余字段由 customer-service 负责。
 */
@Getter
@Setter
@TableName("cst_wallet_account")
public class WalletAccountCurrencyPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long customerId;
    private Long legalEntityId;
    private String currencyCode;
    private Long availableAmount;
    private Long frozenAmount;
    private LocalDateTime updatedAt;
}
