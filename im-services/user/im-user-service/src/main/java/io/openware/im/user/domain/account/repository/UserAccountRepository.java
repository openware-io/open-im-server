package io.openware.im.user.domain.account.repository;

import io.openware.common.dto.PageResult;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.model.UserAccountStatus;
import io.openware.im.user.domain.account.readmodel.UserAccountView;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 账号仓储端口。
 *
 * <p><b>写路径返回聚合与记账快照，读路径返回投影</b>（standards/10 L266-300）：
 * 单条、批量读取与写入返回 {@link Persisted}，让个人资料/管理端详情等响应拿到审计列，
 * 而不必把它们留在领域模型上；`searchForAdmin` 是只读分页查询，返回 {@link UserAccountView} 投影——
 * 投影携带展示所需的审计列，排序留在适配器。
 *
 * <p><b>{@code save} 语义统一为 {@code id == null} 新增、否则更新</b>（L300）。
 */
public interface UserAccountRepository {
  boolean existsByUsername(String username);

  boolean existsByEmail(String email);

  Optional<Persisted<UserAccount>> findById(Long id);

  default List<Persisted<UserAccount>> findByIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return ids.stream().distinct().map(this::findById).flatMap(Optional::stream).toList();
  }

  Optional<Persisted<UserAccount>> findByUsername(String username);

  Optional<Persisted<UserAccount>> findByEmail(String email);

  Optional<Persisted<UserAccount>> findByPhone(String phone);

  List<Persisted<UserAccount>> search(String keyword, int limit);

  /** 管理端用户分页查询：用户名精确、状态、关键字（用户名/昵称模糊或数字 ID 精确）过滤，按 id 倒序。 */
  PageResult<UserAccountView> searchForAdmin(String username, UserAccountStatus status, String keyword,
      Long numericUserId, int page, int pageSize);

  /** 管理端好友关键字匹配：按用户名或昵称模糊匹配返回用户 ID 列表。 */
  List<Long> findIdsMatchingKeyword(String keyword);

  long countAll();

  long countCreatedSince(LocalDateTime since);

  /** 按天统计新增用户数（返回 [{date:'yyyy-MM-dd', count:N}]，按日期升序），用于趋势图。 */
  List<Map<String, Object>> dailyCounts(LocalDateTime since);

  /**
   * 新增或更新（{@code id == null} 新增），返回落库后的聚合与记账快照。
   *
   * <p><b>写入时刻由调用方给出</b>（standards/10 L299）：新增时 {@code occurredAt} 同时写
   * {@code created_at}/{@code updated_at}、{@code created_by}/{@code updated_by} 写系统 {@code 0L}；
   * 更新时只写 {@code updated_at}，{@code created_*} 与 {@code updated_by} 保持库中原值
   * （只有状态变更路径会改 {@code updated_by}，见 {@link #saveStatusIfVersionMatches}）。
   * 适配器不得自行打 {@code now()}——事件/结果使用的时间必须与库里一致。
   */
  Persisted<UserAccount> save(UserAccount account, LocalDateTime occurredAt);

  /**
   * 带乐观版本条件的状态变更：{@code status_version} 不匹配时返回 false，不覆盖并发修改。
   *
   * <p>{@code operatorId} 与 {@code occurredAt} 是这次管理端变更的业务输入：适配器据此写
   * {@code updated_by = operatorId}、{@code updated_at = occurredAt}（{@code created_*} 不动）。
   */
  boolean saveStatusIfVersionMatches(UserAccount account, long expectedStatusVersion, Long operatorId,
      LocalDateTime occurredAt);

  /** 查询自毁策略开启且到期（self_destruct_at <= now）的账号，按到期时间升序，限量。 */
  List<Persisted<UserAccount>> findSelfDestructDue(LocalDateTime now, int limit);

  /** 硬删账号主记录；返回删除行数。 */
  int hardDelete(Long id);
}
