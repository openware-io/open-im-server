package io.openware.common.mail.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 邮件渠道配置：SMTP 参数；password 为平台加密托管占位，不落明文。 */
@Getter
@Setter
@TableName("mail_channel_config")
public class MailChannelConfigPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String host;
    private Integer port;
    private String username;
    private String password;
    private String fromAddress;
    private Boolean enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
