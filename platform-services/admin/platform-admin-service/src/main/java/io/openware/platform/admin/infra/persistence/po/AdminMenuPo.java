package io.openware.platform.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("iam_menu")
public class AdminMenuPo {
    @TableId
    private Long id;
    private Long parentId;
    private String code;
    private String name;
    private String path;
    private String icon;
    private String scopeLevel;
    private String domainCode;
    private String requiredPermission;
    private String requiredGrant;
    private String businessType;
    private String i18nKey;
    private Integer sortNo;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
