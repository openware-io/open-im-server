package io.openware.platform.identity.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("idt_login_identity")
public class LoginIdentityPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long accountId;
    private String loginType;
    private String loginIdentifier;
    private String credential;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
