package com.gvchat.im.user.domain.account.repository;

import com.gvchat.common.dto.PageResult;
import com.gvchat.im.user.domain.account.model.UserAccount;
import com.gvchat.im.user.domain.account.model.UserAccountStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface UserAccountRepository {
  boolean existsByUsername(String username);

  boolean existsByEmail(String email);

  Optional<UserAccount> findById(Long id);

  default List<UserAccount> findByIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return ids.stream().distinct().map(this::findById).flatMap(Optional::stream).toList();
  }

  Optional<UserAccount> findByUsername(String username);

  Optional<UserAccount> findByEmail(String email);

  Optional<UserAccount> findByPhone(String phone);

  List<UserAccount> search(String keyword, int limit);

  /** 管理端用户分页查询：用户名精确、状态、关键字（用户名/昵称模糊或数字 ID 精确）过滤，按 id 倒序。 */
  PageResult<UserAccount> searchForAdmin(String username, UserAccountStatus status, String keyword, Long numericUserId,
      int page, int pageSize);

  /** 管理端好友关键字匹配：按用户名或昵称模糊匹配返回用户 ID 列表。 */
  List<Long> findIdsMatchingKeyword(String keyword);

  long countAll();

  long countCreatedSince(LocalDateTime since);

  /** 按天统计新增用户数（返回 [{date:'yyyy-MM-dd', count:N}]，按日期升序），用于趋势图。 */
  List<Map<String, Object>> dailyCounts(LocalDateTime since);

  UserAccount save(UserAccount account);

  boolean saveStatusIfVersionMatches(UserAccount account, long expectedStatusVersion);

  /** 查询自毁策略开启且到期（self_destruct_at <= now）的账号，按到期时间升序，限量。 */
  List<UserAccount> findSelfDestructDue(LocalDateTime now, int limit);

  /** 硬删账号主记录；返回删除行数。 */
  int hardDelete(Long id);
}
