package com.gvchat.platform.customer.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 客户姓名/客户号盲索引 token（{@code cst_member_name_token}，见迁移 V5）。
 *
 * <p>token = 归一化文本的 1/2/3-gram 的 SHA-256 十六进制；检索要求关键词的所有 token 都命中（AND）。
 * 与 {@code cst_member} 的关系是「一个客户 N 行 token」，重建时**先删后插**，天然幂等。
 */
@Getter
@Setter
@TableName("cst_member_name_token")
public class CstMemberNameTokenPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long memberId;
    /** SHA-256 十六进制，定长 64（char(64)）。 */
    private String token;
    private LocalDateTime createdAt;
}
