package com.gvchat.im.user.infra.persistence.social.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.common.dto.PageResult;
import com.gvchat.im.user.domain.social.model.FriendRelation;
import com.gvchat.im.user.domain.social.model.FriendStatus;
import com.gvchat.im.user.domain.social.repository.FriendRelationRepository;
import com.gvchat.im.user.infra.persistence.social.converter.FriendPersistenceConverter;
import com.gvchat.im.user.infra.persistence.social.mapper.FriendRelationMapper;
import com.gvchat.im.user.infra.persistence.social.po.FriendRelationPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FriendRelationRepositoryAdapter implements FriendRelationRepository {
  private final FriendRelationMapper mapper;

  @Override
  public Optional<FriendRelation> findByUserIdAndFriendId(Long userId, Long friendId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<FriendRelationPo>lambdaQuery()
        .eq(FriendRelationPo::getUserId, userId)
        .eq(FriendRelationPo::getFriendId, friendId))).map(FriendPersistenceConverter::toDomain);
  }

  @Override
  public List<FriendRelation> findByUserIdAndStatusOrderByGroupNameAndRemark(Long userId, FriendStatus status) {
    return mapper.selectList(Wrappers.<FriendRelationPo>lambdaQuery()
            .eq(FriendRelationPo::getUserId, userId)
            .eq(FriendRelationPo::getStatus, status.name().toLowerCase())
            .orderByAsc(FriendRelationPo::getGroupName, FriendRelationPo::getRemark))
        .stream().map(FriendPersistenceConverter::toDomain).toList();
  }

  @Override
  public List<FriendRelation> findByFriendIdAndStatus(Long friendId, FriendStatus status) {
    return mapper.selectList(Wrappers.<FriendRelationPo>lambdaQuery()
            .eq(FriendRelationPo::getFriendId, friendId)
            .eq(FriendRelationPo::getStatus, status.name().toLowerCase()))
        .stream().map(FriendPersistenceConverter::toDomain).toList();
  }

  @Override
  public List<String> findGroupNamesByUserId(Long userId) {
    return mapper.selectObjs(Wrappers.<FriendRelationPo>query().select("DISTINCT group_name")
        .eq("user_id", userId).orderByAsc("group_name")).stream().map(String::valueOf).toList();
  }

  @Override
  public FriendRelation save(FriendRelation relation) {
    FriendRelationPo po = FriendPersistenceConverter.toPo(relation);
    if (po.getId() == null) {
      mapper.insert(po);
      relation.assignId(po.getId());
    } else {
      mapper.updateById(po);
    }
    return relation;
  }

  @Override
  public void deleteByUserIdAndFriendId(Long userId, Long friendId) {
    mapper.delete(Wrappers.<FriendRelationPo>lambdaQuery().eq(FriendRelationPo::getUserId, userId)
        .eq(FriendRelationPo::getFriendId, friendId));
  }

  @Override
  public PageResult<FriendRelation> searchForAdmin(FriendStatus status, String groupName, String keyword,
      List<Long> matchedUserIds, Long numericUserId, int page, int pageSize) {
    LambdaQueryWrapper<FriendRelationPo> query = Wrappers.<FriendRelationPo>lambdaQuery()
        .eq(status != null, FriendRelationPo::getStatus, status == null ? null : status.name().toLowerCase())
        .eq(groupName != null && !groupName.isBlank(), FriendRelationPo::getGroupName, groupName);
    if (keyword != null && !keyword.isBlank()) {
      query.and(wrapper -> {
        wrapper.like(FriendRelationPo::getRemark, keyword);
        if (matchedUserIds != null && !matchedUserIds.isEmpty()) {
          wrapper.or().in(FriendRelationPo::getUserId, matchedUserIds)
              .or().in(FriendRelationPo::getFriendId, matchedUserIds);
        }
        if (numericUserId != null) {
          wrapper.or().eq(FriendRelationPo::getUserId, numericUserId)
              .or().eq(FriendRelationPo::getFriendId, numericUserId);
        }
      });
    }
    Page<FriendRelationPo> result = mapper.selectPage(new Page<>(page, pageSize),
        query.orderByDesc(FriendRelationPo::getId));
    return toPage(result, FriendPersistenceConverter::toDomain);
  }

  @Override
  public long countAll() {
    return mapper.selectCount(null);
  }

  @Override
  public long countCreatedSince(LocalDateTime since) {
    return mapper.selectCount(Wrappers.<FriendRelationPo>lambdaQuery().ge(FriendRelationPo::getCreatedAt, since));
  }

  @Override
  public List<Map<String, Object>> dailyCounts(LocalDateTime since) {
    QueryWrapper<FriendRelationPo> wrapper = new QueryWrapper<FriendRelationPo>()
        .select("DATE_FORMAT(created_at, '%Y-%m-%d') AS date", "COUNT(*) AS count")
        .ge("created_at", since)
        .groupBy("DATE_FORMAT(created_at, '%Y-%m-%d')")
        .orderByAsc("date");
    return mapper.selectMaps(wrapper);
  }

  private static <T> PageResult<T> toPage(Page<FriendRelationPo> source,
      java.util.function.Function<FriendRelationPo, T> converter) {
    return PageResult.<T>builder().items(source.getRecords().stream().map(converter).toList()).total(source.getTotal())
        .page((int) source.getCurrent()).pageSize((int) source.getSize()).build();
  }
}
