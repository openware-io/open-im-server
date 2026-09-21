package com.gvchat.im.user.infra.persistence.account.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.common.dto.PageResult;
import com.gvchat.im.user.domain.account.model.UserAccount;
import com.gvchat.im.user.domain.account.model.UserAccountStatus;
import com.gvchat.im.user.domain.account.repository.UserAccountRepository;
import com.gvchat.im.user.infra.persistence.account.converter.UserAccountPersistenceConverter;
import com.gvchat.im.user.infra.persistence.account.mapper.UserAccountMapper;
import com.gvchat.im.user.infra.persistence.account.po.UserAccountPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

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
  public Optional<UserAccount> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(UserAccountPersistenceConverter::toDomain);
  }

  @Override
  public List<UserAccount> findByIds(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return mapper.selectList(Wrappers.<UserAccountPo>lambdaQuery().in(UserAccountPo::getId, ids)).stream()
        .map(UserAccountPersistenceConverter::toDomain)
        .toList();
  }

  @Override
  public Optional<UserAccount> findByUsername(String username) {
    return Optional.ofNullable(mapper.selectOne(
        Wrappers.<UserAccountPo>lambdaQuery().eq(UserAccountPo::getUsername, username)))
        .map(UserAccountPersistenceConverter::toDomain);
  }

  @Override
  public Optional<UserAccount> findByEmail(String email) {
    return Optional.ofNullable(mapper.selectOne(
        Wrappers.<UserAccountPo>lambdaQuery().eq(UserAccountPo::getEmail, email)))
        .map(UserAccountPersistenceConverter::toDomain);
  }

  @Override
  public Optional<UserAccount> findByPhone(String phone) {
    return Optional.ofNullable(mapper.selectOne(
        Wrappers.<UserAccountPo>lambdaQuery().eq(UserAccountPo::getPhone, phone)))
        .map(UserAccountPersistenceConverter::toDomain);
  }

  @Override
  public List<UserAccount> search(String keyword, int limit) {
    return mapper.selectList(Wrappers.<UserAccountPo>lambdaQuery()
            .and(wrapper -> wrapper.like(UserAccountPo::getUsername, keyword)
                .or().like(UserAccountPo::getNickname, keyword)
                .or().eq(UserAccountPo::getPhone, keyword))
            .last("LIMIT " + limit))
        .stream()
        .map(UserAccountPersistenceConverter::toDomain)
        .toList();
  }

  @Override
  public UserAccount save(UserAccount account) {
    UserAccountPo po = UserAccountPersistenceConverter.toPo(account);
    if (po.getId() == null) {
      mapper.insert(po);
      account.assignId(po.getId());
    } else {
      mapper.updateById(po);
    }
    return account;
  }

  @Override
  public boolean saveStatusIfVersionMatches(UserAccount account, long expectedStatusVersion) {
    UserAccountPo po = UserAccountPersistenceConverter.toPo(account);
    return mapper.update(po, Wrappers.<UserAccountPo>lambdaUpdate()
        .eq(UserAccountPo::getId, account.getId())
        .eq(UserAccountPo::getStatusVersion, expectedStatusVersion)) == 1;
  }

  @Override
  public List<UserAccount> findSelfDestructDue(LocalDateTime now, int limit) {
    return mapper.selectList(Wrappers.<UserAccountPo>lambdaQuery()
            .ne(UserAccountPo::getSelfDestructPolicy, "off")
            .le(UserAccountPo::getSelfDestructAt, now)
            .orderByAsc(UserAccountPo::getSelfDestructAt)
            .last("LIMIT " + limit))
        .stream()
        .map(UserAccountPersistenceConverter::toDomain)
        .toList();
  }

  @Override
  public int hardDelete(Long id) {
    return mapper.deleteById(id);
  }

  @Override
  public PageResult<UserAccount> searchForAdmin(String username, UserAccountStatus status, String keyword,
      Long numericUserId, int page, int pageSize) {
    LambdaQueryWrapper<UserAccountPo> query = Wrappers.<UserAccountPo>lambdaQuery()
        .eq(username != null && !username.isBlank(), UserAccountPo::getUsername, username)
        .eq(status != null, UserAccountPo::getStatus, status == null ? null : status.name().toLowerCase());
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
    return toPage(result, UserAccountPersistenceConverter::toDomain);
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

  private static <T> PageResult<T> toPage(Page<UserAccountPo> source,
      java.util.function.Function<UserAccountPo, T> converter) {
    return PageResult.<T>builder().items(source.getRecords().stream().map(converter).toList()).total(source.getTotal())
        .page((int) source.getCurrent()).pageSize((int) source.getSize()).build();
  }
}
