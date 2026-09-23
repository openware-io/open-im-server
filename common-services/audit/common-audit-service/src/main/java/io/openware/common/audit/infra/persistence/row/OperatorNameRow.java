package io.openware.common.audit.infra.persistence.row;

import lombok.Getter;
import lombok.Setter;

/**
 * 操作人展示信息只读行（{@code open_saas.saa_admin_account}），仅用于审计列表/详情补全姓名与账号，
 * 不参与任何写入：账号表的权威值只读，审计表里的历史值不被覆盖。
 */
@Getter
@Setter
public class OperatorNameRow {

    /** 关联键：{@code saa_admin_account.platform_account_id}，即审计表的 {@code operator_id}。 */
    private Long operatorId;

    /** 登录名/工号 → 审计表的 {@code operator_account}。 */
    private String username;

    /** 姓名 → 审计表的 {@code operator_name}。 */
    private String displayName;
}
