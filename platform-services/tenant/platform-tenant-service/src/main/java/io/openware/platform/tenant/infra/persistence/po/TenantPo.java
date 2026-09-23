package io.openware.platform.tenant.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("tnt_tenant")
public class TenantPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantCode;
    private String name;
    private String status;
    private String defaultLocale;
    private String defaultTimezone;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
