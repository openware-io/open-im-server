package io.openware.platform.customer.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/**
 * 客户档案（{@code cst_member}）。
 *
 * <p>口径：这张表叫「会员」但实际存的是**客户**——等级（level_id）/权益/成长值业务都没有实现，
 * 界面与菜单统一按「客户」展示（字段名与接口路径保持 member 不动，避免授权与路由漂移）。
 *
 * <p><b>字段分两类</b>：
 * <ul>
 *   <li><b>持久化列</b>：与 {@code cst_member} 的列一一对应（含 V4 新增的 im_account / im_username /
 *       im_bound_at）；</li>
 *   <li><b>非持久化展示字段</b>（{@code @TableField(exist = false)}）：name/phone 是读取时用
 *       {@code AesGcmCipher} 解出来的明文回显（无 {@code member.pii.view} 权限时是脱敏值），
 *       既不落库也不参与写。</li>
 * </ul>
 */
@Getter
@Setter
@TableName("cst_member")
public class CstMemberPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    /** 持久化列：SaaS 账号 idt_account.id；NULL = 待认领客户。 */
    private Long accountId;
    /** 持久化列（V4）：IM 登录标识（如 im_71 / openId）；NULL = 未绑定 IM。 */
    private String imAccount;
    /** 持久化列（V4）：IM 用户名/昵称快照。 */
    private String imUsername;
    /** 持久化列（V4）：IM 绑定时间。 */
    private LocalDateTime imBoundAt;
    private String memberNo;
    private String nameCipher;
    private String phoneCipher;
    private String phoneDigest;
    /** 非持久化展示字段：解密后的姓名（读回显用）。 */
    @TableField(exist = false)
    private String name;
    /** 非持久化展示字段：解密后的手机号（读回显用）。 */
    @TableField(exist = false)
    private String phone;
    /**
     * 非持久化展示字段：账号类型（{@code idt_account.account_type}：CUSTOMER/EMPLOYEE/PLATFORM_OPERATOR）。
     * 客户档案与运营人员共用一张账号主体表，页面要能看出「这条客户档案挂的是不是一个员工账号」。
     */
    @TableField(exist = false)
    private String accountType;
    /**
     * 非持久化展示字段：IM 侧**真实账号**（{@code im_server.user.username}，如 user600）；
     * 界面上「IM 账号」列应显示它，而不是登录标识 {@code im_<id>}。
     */
    @TableField(exist = false)
    private String imAccountName;
    /** 非持久化展示字段：IM 账号是否已不存在（用户行缺失，或 username 已是 {@code deleted_<id>_<hash>} 形态）。 */
    @TableField(exist = false)
    private Boolean imAccountDeleted;
    private Long levelId;
    private String status;
    private LocalDateTime joinedAt;
    private LocalDateTime expiresAt;
    private Integer version;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
