package io.openware.platform.admin.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("iam_role_profile")
public class AdminRoleProfilePo {
    @TableId
    private Long id;
    private String roleCode;
    private String workspaceCode;
    private String landingRoute;
    private String defaultScope;
    private String defaultBusinessType;
    private String navProfile;
    private Integer sortNo;
    private String status;
}
