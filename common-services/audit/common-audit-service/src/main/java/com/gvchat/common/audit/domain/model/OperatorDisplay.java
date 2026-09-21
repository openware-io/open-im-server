package com.gvchat.common.audit.domain.model;

/**
 * 操作人展示信息（只读补全用）。
 *
 * <p>审计表按合规要求把操作人身份冻结在记录里：{@code operator_id} 是冗余的关联键（指向账号表），
 * 而 {@code operator_name}/{@code operator_account} 允许为空——上报方拿不到姓名时不必伪造，
 * 列表/详情在读取时按 {@code operator_id} 到账号表补出当前展示值。
 *
 * <p>「已存值优先、只在缺失时补」是刻意的：记录里存的是**动作发生当时**的姓名，
 * 账号改名后历史记录不应被追改；补全只解决「当时没存下来」的情况。
 *
 * @param name    姓名（账号表 display_name）
 * @param account 登录名/工号（账号表 username）
 */
public record OperatorDisplay(String name, String account) {

    public static final OperatorDisplay EMPTY = new OperatorDisplay(null, null);

    /** 两者都为空（无需补全）。 */
    public boolean isEmpty() {
        return blank(name) && blank(account);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
