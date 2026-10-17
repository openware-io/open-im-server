package io.openware.im.user.infra.persistence.account.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.dto.PageResult;
import io.openware.im.user.domain.account.model.UserAccount;
import io.openware.im.user.domain.account.model.UserAccountStatus;
import io.openware.im.user.domain.account.readmodel.UserAccountView;
import io.openware.im.user.domain.account.repository.AuditSnapshot;
import io.openware.im.user.domain.account.repository.Persisted;
import io.openware.im.user.domain.account.repository.UserAccountRepository;
import io.openware.im.user.infra.persistence.account.converter.UserAccountPersistenceConverter;
import io.openware.im.user.infra.persistence.account.mapper.UserAccountMapper;
import io.openware.im.user.infra.persistence.account.po.UserAccountPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.function.Function;

@Repository
@RequiredArgsConstructor
public class UserAccountRepositoryAdapter implements UserAccountRepository {
  private final UserAccountMapper mapper;

  @Override
  public boolean existsByUsername(String username) {
    return mapper.exists(Wrappers.<UserAccountPo>lambdaQuery().eq(UserAccountPo::getUsername, username));
  }

  @Override
  public boolean existsByEmail(String email) {
    return mapper.exists(Wrappers.<UserAccountPo>lambdaQuery().eq(UserAccountPo::getEmail, email));
  }

  @Override
  public Optional<Persisted<UserAccount>> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toPersisted);
  }

  @Override
  public List<Persisted<UserAccount>> findByIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return mapper.selectList(Wrappers.<UserAccountPo>lambdaQuery().in(UserAccountPo::getId, ids)).stream()
        .map(this::toPersisted)
        .toList();
  }

  @Override
  public Optional<Persisted<UserAccount>> findByUsername(String username) {
    return Optional.ofNullable(mapper.selectOne(
        Wrappers.<UserAccountPo>lambdaQuery().eq(UserAccountPo::getUsername, username)))
        .map(this::toPersisted);
  }

  @Override
  public Optional<Persisted<UserAccount>> findByEmail(String email) {
    return Optional.ofNullable(mapper.selectOne(
        Wrappers.<UserAccountPo>lambdaQuery().eq(UserAccountPo::getEmail, email)))
        .map(this::toPersisted);
  }

  @Override
  public Optional<Persisted<UserAccount>> findByPhone(String phone) {
    return Optional.ofNullable(mapper.selectOne(
        Wrappers.<UserAccountPo>lambdaQuery().eq(UserAccountPo::getPhone, phone)))
        .map(this::toPersisted);
  }

  @Override
  public List<Persisted<UserAccount>> search(String keyword, int limit) {
    return mapper.selectList(Wrappers.<UserAccountPo>lambdaQuery()
            .and(wrapper -> wrapper.like(UserAccountPo::getUsername, keyword)
                .or().like(UserAccountPo::getNickname, keyword)
                .or().eq(UserAccountPo::getPhone, keyword))
            .last("LIMIT " + limit))
        .stream()
        .map(this::toPersisted)
        .toList();
  }

  @Override
  public Persisted<UserAccount> save(UserAccount account, LocalDateTime occurredAt) {
    UserAccountPo po = UserAccountPersistenceConverter.toPo(account);
    if (po.getId() == null) {
      po.setCreatedBy(0L);
      po.setCreatedAt(occurredAt);
      po.setUpdatedBy(0L);
      po.setUpdatedAt(occurredAt);
      mapper.insert(po);
      account.assignId(po.getId());
      return toPersisted(po);
    }
    // 普通更新只写 updated_at；created_* 与 updated_by 沿用库中原值。
    po.setUpdatedAt(occurredAt);
    mapper.updateById(po);
    return toPersisted(mapper.selectById(po.getId()));
  }

  @Override
  public boolean saveStatusIfVersionMatches(UserAccount account, long expectedStatusVersion, Long operatorId,
      LocalDateTime occurredAt) {
    UserAccountPo po = UserAccountPersistenceConverter.toPo(account);
    // 状态变更路径显式记录操作人与时刻；created_* 保持不变。
    po.setUpdatedBy(operatorId);
    po.setUpdatedAt(occurredAt);
    return mapper.update(po, Wrappers.<UserAccountPo>lambdaUpdate()
        .eq(UserAccountPo::getId, account.getId())
        .eq(UserAccountPo::getStatusVersion, expectedStatusVersion)) == 1;
  }

  @Override
  public List<Persisted<UserAccount>> findSelfDestructDue(LocalDateTime now, int limit) {
    return mapper.selectList(Wrappers.<UserAccountPo>lambdaQuery()
            .ne(UserAccountPo::getSelfDestructPolicy, "off")
            .le(UserAccountPo::getSelfDestructAt, now)
            .orderByAsc(UserAccountPo::getSelfDestructAt)
            .last("LIMIT " + limit))
        .stream()
        .map(this::toPersisted)
        .toList();
  }

  @Override
  public int hardDelete(Long id) {
    return mapper.deleteById(id);
  }

  @Override
  public PageResult<UserAccountView> searchForAdmin(String username, UserAccountStatus status, String keyword,
      Long numericUserId, int page, int pageSize) {
    LambdaQueryWrapper<UserAccountPo> query = Wrappers.<UserAccountPo>lambdaQuery()
        .eq(username != null && !username.isBlank(), UserAccountPo::getUsername, username)
        .eq(status != null, UserAccountPo::getStatus, status == null ? null : status.name().toLowerCase())
        // 主动注销账号保留在表内，但注销状态不进入可管理用户列表。
        .ne(UserAccountPo::getStatus, UserAccountStatus.DISABLED.name().toLowerCase());
    if (keyword != null && !keyword.isBlank()) {
      query.and(wrapper -> {
        wrapper.like(UserAccountPo::getUsername, keyword).or().like(UserAccountPo::getNickname, keyword);
        if (numericUserId != null) {
          wrapper.or().eq(UserAccountPo::getId, numericUserId);
        }
      });
    }
    Page<UserAccountPo> result = mapper.selectPage(new Page<>(page, pageSize),
        query.orderByDesc(UserAccountPo::getId));
    return toPage(result, UserAccountPersistenceConverter::toView);
  }

  @Override
  public List<Long> findIdsMatchingKeyword(String keyword) {
    return mapper.selectList(Wrappers.<UserAccountPo>lambdaQuery()
            .select(UserAccountPo::getId)
            .and(wrapper -> wrapper.like(UserAccountPo::getUsername, keyword).or()
                .like(UserAccountPo::getNickname, keyword)))
        .stream().map(UserAccountPo::getId).toList();
  }

  @Override
  public long countAll() {
    return mapper.selectCount(null);
  }

  @Override
  public long countCreatedSince(LocalDateTime since) {
    return mapper.selectCount(Wrappers.<UserAccountPo>lambdaQuery().ge(UserAccountPo::getCreatedAt, since));
  }

  @Override
  public List<Map<String, Object>> dailyCounts(LocalDateTime since) {
    QueryWrapper<UserAccountPo> wrapper = new QueryWrapper<UserAccountPo>()
        .select("DATE_FORMAT(created_at, '%Y-%m-%d') AS date", "COUNT(*) AS count")
        .ge("created_at", since)
        .groupBy("DATE_FORMAT(created_at, '%Y-%m-%d')")
        .orderByAsc("date");
    return mapper.selectMaps(wrapper);
  }

  /** 业务聚合 + 记账快照：个人资料/管理端详情响应要回显审计列，而它们不进领域模型。 */
  private Persisted<UserAccount> toPersisted(UserAccountPo po) {
    if (po == null) {
      return null;
    }
    return new Persisted<>(UserAccountPersistenceConverter.toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()));
  }

  private static <T> PageResult<T> toPage(Page<UserAccountPo> source,
      Function<UserAccountPo, T> converter) {
    return PageResult.<T>builder().items(source.getRecords().stream().map(converter).toList()).total(source.getTotal())
        .page((int) source.getCurrent()).pageSize((int) source.getSize()).build();
  }
}
