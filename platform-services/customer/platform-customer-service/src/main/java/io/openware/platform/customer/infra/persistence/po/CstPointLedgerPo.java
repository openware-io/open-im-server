package io.openware.platform.customer.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 积分流水（cst_point_ledger），只追加。
 */
@Getter
@Setter
@TableName("cst_point_ledger")
public class CstPointLedgerPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long accountId;
    private String entryType;
    private Long points;
    /** 币种快照（该笔积分调整所依据的租户币种口径；积分本身非货币资产）。 */
    private String currencyCode;
    private Long balanceAfter;
    private String businessType;
    private Long businessId;
    private String idempotencyKey;
    private String ruleSnapshotJson;
    private LocalDateTime occurredAt;
    private Long createdBy;
    private LocalDateTime createdAt;
}
