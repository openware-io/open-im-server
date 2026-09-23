package io.openware.platform.tenant.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 门店（tnt_store）。
 */
@Getter
@Setter
@TableName("tnt_store")
public class StorePo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long organizationId;
    private String code;
    private String name;
    private String businessType;
    private String countryCode;
    private String regionCode;
    private String timezone;
    private String defaultCurrency;
    private String locale;
    private Long taxProfileId;
    private String businessDayCutoff;
    private String status;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
