package com.gvchat.im.user.domain.social.repository;

import com.gvchat.common.dto.PageResult;
import com.gvchat.im.user.domain.social.model.FriendRelation;
import com.gvchat.im.user.domain.social.model.FriendStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface FriendRelationRepository {
  Optional<FriendRelation> findByUserIdAndFriendId(Long userId, Long friendId);
  List<FriendRelation> findByUserIdAndStatusOrderByGroupNameAndRemark(Long userId, FriendStatus status);
  List<FriendRelation> findByFriendIdAndStatus(Long friendId, FriendStatus status);
  List<String> findGroupNamesByUserId(Long userId);
  FriendRelation save(FriendRelation relation);
  void deleteByUserIdAndFriendId(Long userId, Long friendId);

  /** 管理端好友分页查询：状态、分组、关键字（备注模糊、用户名/昵称匹配到的用户、数字 ID）过滤，按 id 倒序。 */
  PageResult<FriendRelation> searchForAdmin(FriendStatus status, String groupName, String keyword,
      List<Long> matchedUserIds, Long numericUserId, int page, int pageSize);

  long countAll();

  long countCreatedSince(LocalDateTime since);

  /** 按天统计新增好友关系数（返回 [{date:'yyyy-MM-dd', count:N}]，按日期升序），用于趋势图。 */
  List<Map<String, Object>> dailyCounts(LocalDateTime since);
}
