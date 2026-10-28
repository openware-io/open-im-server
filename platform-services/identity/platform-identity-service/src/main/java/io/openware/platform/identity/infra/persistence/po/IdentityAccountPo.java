package io.openware.platform.identity.infra.persistence.po;

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
    /** 账号类型 PLATFORM_OPERATOR/USER；员工/客户由 tnt_membership 区分。 */
    private String accountType;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
