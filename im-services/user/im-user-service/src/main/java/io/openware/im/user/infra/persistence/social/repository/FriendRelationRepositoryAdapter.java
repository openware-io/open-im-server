package io.openware.im.user.infra.persistence.social.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.dto.PageResult;
import io.openware.im.user.domain.social.model.FriendRelation;
import io.openware.im.user.domain.social.model.FriendStatus;
import io.openware.im.user.domain.social.readmodel.FriendRelationView;
import io.openware.im.user.domain.social.repository.AuditSnapshot;
import io.openware.im.user.domain.social.repository.FriendRelationRepository;
import io.openware.im.user.domain.social.repository.Persisted;
import io.openware.im.user.infra.persistence.social.converter.FriendPersistenceConverter;
import io.openware.im.user.infra.persistence.social.mapper.FriendRelationMapper;
import io.openware.im.user.infra.persistence.social.po.FriendRelationPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.function.Function;

@Repository
@RequiredArgsConstructor
public class FriendRelationRepositoryAdapter implements FriendRelationRepository {
  private final FriendRelationMapper mapper;

  @Override
  public Optional<Persisted<FriendRelation>> findByUserIdAndFriendId(Long userId, Long friendId) {
    return Optional.ofNullable(mapper.selectOne(Wrappers.<FriendRelationPo>lambdaQuery()
        .eq(FriendRelationPo::getUserId, userId)
        .eq(FriendRelationPo::getFriendId, friendId))).map(this::toPersisted);
  }

  @Override
  public List<Persisted<FriendRelation>> findByUserIdAndStatusOrderByGroupNameAndRemark(Long userId, FriendStatus status) {
    return mapper.selectList(Wrappers.<FriendRelationPo>lambdaQuery()
            .eq(FriendRelationPo::getUserId, userId)
            .eq(FriendRelationPo::getStatus, status.name().toLowerCase())
            .orderByAsc(FriendRelationPo::getGroupName, FriendRelationPo::getRemark))
        .stream().map(this::toPersisted).toList();
  }

  @Override
  public List<Persisted<FriendRelation>> findByFriendIdAndStatus(Long friendId, FriendStatus status) {
    return mapper.selectList(Wrappers.<FriendRelationPo>lambdaQuery()
            .eq(FriendRelationPo::getFriendId, friendId)
            .eq(FriendRelationPo::getStatus, status.name().toLowerCase()))
        .stream().map(this::toPersisted).toList();
  }

  @Override
  public List<String> findGroupNamesByUserId(Long userId) {
    return mapper.selectObjs(Wrappers.<FriendRelationPo>query().select("DISTINCT group_name")
        .eq("user_id", userId).orderByAsc("group_name")).stream().map(String::valueOf).toList();
  }

  @Override
  public Persisted<FriendRelation> save(FriendRelation relation) {
    FriendRelationPo po = FriendPersistenceConverter.toPo(relation);
    // 审计列由适配器确定（standards/10 L299）：关系操作人恒为关系归属用户。
    LocalDateTime now = LocalDateTime.now();
    po.setCreatedBy(po.getUserId());
    po.setUpdatedBy(po.getUserId());
    if (po.getId() == null) {
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      mapper.insert(po);
      relation.assignId(po.getId());
      return toPersisted(po);
    }
    po.setUpdatedAt(now);
    mapper.updateById(po);
    return toPersisted(mapper.selectById(po.getId()));
  }

  /** 业务聚合 + 记账快照：响应回显与授权版本号都要读建/改时刻，而它们不进领域模型。 */
  private Persisted<FriendRelation> toPersisted(FriendRelationPo po) {
    if (po == null) {
      return null;
    }
    return new Persisted<>(FriendPersistenceConverter.toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()));
  }

  /** 管理端列表投影：只读聚合查询，不参与状态变更（standards/10 L266-279）。 */
  private FriendRelationView toView(FriendRelationPo po) {
    return new FriendRelationView(po.getId(), po.getUserId(), po.getFriendId(), po.getRemark(), po.getGroupName(),
        FriendStatus.valueOf(po.getStatus().toUpperCase()), po.getCreatedAt());
  }

  @Override
  public void deleteByUserIdAndFriendId(Long userId, Long friendId) {
    mapper.delete(Wrappers.<FriendRelationPo>lambdaQuery().eq(FriendRelationPo::getUserId, userId)
        .eq(FriendRelationPo::getFriendId, friendId));
  }

  @Override
  public PageResult<FriendRelationView> searchForAdmin(FriendStatus status, String groupName, String keyword,
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
    return toPage(result, this::toView);
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
      Function<FriendRelationPo, T> converter) {
    return PageResult.<T>builder().items(source.getRecords().stream().map(converter).toList()).total(source.getTotal())
        .page((int) source.getCurrent()).pageSize((int) source.getSize()).build();
  }
}
