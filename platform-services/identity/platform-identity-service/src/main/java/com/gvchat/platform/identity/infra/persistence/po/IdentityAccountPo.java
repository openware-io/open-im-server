package com.gvchat.platform.identity.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("idt_account")
public class IdentityAccountPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String status;
    /** 账号类型 EMPLOYEE/CUSTOMER/PLATFORM_OPERATOR（统一账号模型：员工/客户共表，按类型区分）。 */
    private String accountType;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
