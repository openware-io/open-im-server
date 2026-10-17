package io.openware.im.user.domain.account.readmodel;

import io.openware.im.user.domain.account.model.UserAccountRole;
import io.openware.im.user.domain.account.model.UserAccountStatus;
import java.time.LocalDateTime;

/**
 * 管理端用户列表的只读投影（standards/10 L266-279）。
 *
 * <p>列表是只读聚合查询、结果不参与状态变更决策，因此用投影而非领域模型；
 * 投影不定义业务行为、不被写路径当实体使用，排序（{@code id DESC}）留在适配器。
 *
 * <p><b>为什么这里可以带审计字段</b>：审计列不进**领域模型**（L299），但管理端响应要回显
 * {@code createdAt/updatedAt}。投影是查询结果类型而非聚合，天然承载这类展示字段。
 * 密码哈希刻意不进入投影：管理端列表不需要它。
 *
 * @param id            账号标识
 * @param username      用户名
 * @param nickname      昵称
 * @param avatar        头像对象 id
 * @param email         邮箱
 * @param phone         手机号
 * @param signature     个性签名
 * @param status        账号状态
 * @param statusVersion 状态版本号
 * @param role          角色
 * @param createdAt     创建时间（审计列）
 * @param updatedAt     最后更新时间（审计列）
 */
public record UserAccountView(Long id, String username, String nickname, String avatar, String email, String phone,
    String signature, UserAccountStatus status, long statusVersion, UserAccountRole role,
    LocalDateTime createdAt, LocalDateTime updatedAt) {
}
