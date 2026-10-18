package io.openware.platform.customer.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.customer.domain.member.model.Member;
import io.openware.platform.customer.domain.member.model.MemberFilter;
import io.openware.platform.customer.domain.member.repository.MemberRepository;
import io.openware.platform.customer.infra.persistence.mapper.MemberMapper;
import io.openware.platform.customer.infra.persistence.po.CstMemberPo;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/**
 * {@link MemberRepository} 的持久化适配器。
 *
 * <p><b>列集合只来自真实 DDL</b>：{@code CstMemberPo} 上有五个 {@code @TableField(exist = false)}
 * 的展示字段（{@code name} / {@code phone} / {@code accountType} / {@code imAccountName} /
 * {@code imAccountDeleted}），它们不是列。这里<b>只 set 真实列</b>——对不存在的列生成引用会在运行时抛
 * {@code can not find lambda cache for this property}，而 {@code test-compile} 完全干净
 * （钱包切片上真实发生过，只有集成测试抓到）。
 *
 * <p>分页用 MyBatis-Plus 的 {@code Page} 承载<b>偏移与总数</b>，但它不出现在端口签名里：
 * 端口只回 {@code ids + total}，由应用层重新装配分页对象。
 */
@Slf4j
@Repository
public class MemberRepositoryAdapter implements MemberRepository {

  private final MemberMapper memberMapper;

  public MemberRepositoryAdapter(MemberMapper memberMapper) {
    this.memberMapper = memberMapper;
  }

  @Override
  public Optional<Member> findById(Long id) {
    if (id == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(memberMapper.selectById(id)).map(MemberRepositoryAdapter::toDomain);
  }

  @Override
  public Optional<Member> findEarliestByAccountId(Long accountId) {
    if (accountId == null) {
      return Optional.empty();
    }
    List<CstMemberPo> rows = memberMapper.selectList(Wrappers.<CstMemberPo>lambdaQuery()
        .eq(CstMemberPo::getAccountId, accountId)
        .orderByAsc(CstMemberPo::getId)
        .last("LIMIT 2"));
    return earliest(rows, "同一 accountId 命中多条客户档案（历史脏数据；迁移 V6 已去重「无引用」的重复行）: "
        + "accountId=" + accountId);
  }

  @Override
  public Optional<Member> findEarliestByImAccount(String imAccount) {
    if (imAccount == null || imAccount.isBlank()) {
      return Optional.empty();
    }
    List<CstMemberPo> rows = memberMapper.selectList(Wrappers.<CstMemberPo>lambdaQuery()
        .eq(CstMemberPo::getImAccount, imAccount)
        .orderByAsc(CstMemberPo::getId)
        .last("LIMIT 2"));
    return earliest(rows, "同一 IM 账号命中多条客户档案（应被 uk_cst_member_tenant_im 拦住）: imAccount="
        + imAccount);
  }

  /** 取最早一条；命中多行打 WARN 但不炸（历史脏数据口径）。 */
  private static Optional<Member> earliest(List<CstMemberPo> rows, String duplicatedWarning) {
    if (rows == null || rows.isEmpty()) {
      return Optional.empty();
    }
    if (rows.size() > 1) {
      log.warn("{}, 取最早一条 id={}", duplicatedWarning, rows.get(0).getId());
    }
    return Optional.of(toDomain(rows.get(0)));
  }

  @Override
  public MemberIdPage listIds(MemberFilter filter) {
    // 分页用显式 LIMIT/OFFSET（与钱包/积分适配器同一写法）：端口给的是偏移 + 上限，
    // 必须原样落到 SQL —— 依赖 MyBatis-Plus 分页插件按"页码"换算会在偏移不是页大小整数倍时悄悄错页。
    long safeLimit = Math.max(1L, filter.limit());
    long safeOffset = Math.max(0L, filter.offset());
    List<CstMemberPo> records = memberMapper.selectList(
        conditions(filter).orderByDesc(CstMemberPo::getId)
            .last("LIMIT " + safeLimit + " OFFSET " + safeOffset));
    // 计数用**同一个** WHERE 但不带 ORDER BY / LIMIT：count 语句里带 ORDER BY 在 H2 上直接报
    // "Column id must be in the GROUP BY list"，MySQL 上也毫无意义（实测过）。
    long total = memberMapper.selectCount(conditions(filter));
    List<Long> ids = records.stream().map(CstMemberPo::getId).toList();
    return new MemberIdPage(ids, total);
  }

  /** 过滤条件（不含排序与分页）：分页查询与计数查询必须共用它，否则 total 与过滤口径会漂移。 */
  private static LambdaQueryWrapper<CstMemberPo> conditions(MemberFilter filter) {
    LambdaQueryWrapper<CstMemberPo> wrapper = Wrappers.lambdaQuery();
    applyKeyword(wrapper, filter);
    if (filter.levelId() != null) {
      wrapper.eq(CstMemberPo::getLevelId, filter.levelId());
    }
    if (filter.status() != null && !filter.status().isBlank()) {
      wrapper.eq(CstMemberPo::getStatus, filter.status());
    }
    if (filter.onlyUnlinked()) {
      wrapper.isNull(CstMemberPo::getImAccount);
    }
    if (filter.originStoreId() != null) {
      wrapper.eq(CstMemberPo::getOriginStoreId, filter.originStoreId());
    }
    // 建档时间闭区间：条件直接拼在 joined_at 列上（不包函数，走索引）。
    wrapper.ge(filter.joinedFrom() != null, CstMemberPo::getJoinedAt, filter.joinedFrom());
    wrapper.le(filter.joinedTo() != null, CstMemberPo::getJoinedAt, filter.joinedTo());
    return wrapper;
  }

  /**
   * 关键词检索的四个 OR 分支（一次 SQL 完成，因此分页与 total 口径一致）：
   * 客户号包含 / 姓名盲索引 token 全命中 / 手机号摘要精确 / IM 账号与用户名包含。
   *
   * <p>盲索引候选集为空时<b>只跳过该分支</b>，不回空页：关键词是手机号或 IM 账号时盲索引必然无命中，
   * 回空页会把②③两条通道一起废掉。空集合也不会拼出非法的 {@code IN ()}（MyBatis-Plus 对空集合会生成非法 SQL）。
   */
  private static void applyKeyword(LambdaQueryWrapper<CstMemberPo> wrapper, MemberFilter filter) {
    if (!filter.hasKeyword()) {
      return;
    }
    String keyword = filter.keyword().trim();
    wrapper.and(w -> {
      w.like(CstMemberPo::getMemberNo, keyword);
      w.or().like(CstMemberPo::getImAccount, keyword);
      w.or().like(CstMemberPo::getImUsername, keyword);
      if (filter.phoneDigest() != null) {
        w.or().eq(CstMemberPo::getPhoneDigest, filter.phoneDigest());
      }
      if (filter.nameMatchIds() != null && !filter.nameMatchIds().isEmpty()) {
        w.or().in(CstMemberPo::getId, filter.nameMatchIds());
      }
    });
  }

  @Override
  public List<Member> findAllById(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    Map<Long, Member> byId = new LinkedHashMap<>();
    for (CstMemberPo po : memberMapper.selectList(
        Wrappers.<CstMemberPo>lambdaQuery().in(CstMemberPo::getId, ids))) {
      byId.put(po.getId(), toDomain(po));
    }
    List<Member> ordered = new ArrayList<>(ids.size());
    for (Long id : ids) {
      Member member = byId.get(id);
      if (member != null) {
        ordered.add(member);
      }
    }
    return ordered;
  }

  @Override
  public long countAll() {
    return memberMapper.selectCount(Wrappers.lambdaQuery());
  }

  @Override
  public Member insert(Member member) {
    LocalDateTime now = LocalDateTime.now();
    CstMemberPo po = new CstMemberPo();
    po.setTenantId(member.tenantId());
    po.setOriginStoreId(member.originStoreId());
    po.setAccountId(member.accountId());
    po.setImAccount(member.imAccount());
    po.setImUsername(member.imUsername());
    po.setImBoundAt(member.imBoundAt());
    po.setMemberNo(member.memberNo());
    po.setNameCipher(member.nameCipher());
    po.setPhoneCipher(member.phoneCipher());
    po.setPhoneDigest(member.phoneDigest());
    po.setLevelId(member.levelId());
    po.setStatus(member.status());
    po.setJoinedAt(member.joinedAt());
    po.setExpiresAt(member.expiresAt());
    po.setVersion(member.version() == null ? 0 : member.version());
    po.setCreatedAt(now);
    po.setUpdatedAt(now);
    // 不设 name / phone / accountType / imAccountName / imAccountDeleted：它们是 exist = false 的展示字段。
    // 唯一键（租户 + 客户号 / 账号 / IM 账号）冲突必须抛出：吞掉它会让调用方以为档案已落库。
    memberMapper.insert(po);
    return toDomain(po);
  }

  @Override
  public boolean updateImBinding(Long id, String imAccount, String imUsername, LocalDateTime imBoundAt,
                                 String newNameCipher, int expectedVersion) {
    LambdaUpdateWrapper<CstMemberPo> update = Wrappers.<CstMemberPo>lambdaUpdate()
        .eq(CstMemberPo::getId, id)
        .eq(CstMemberPo::getVersion, expectedVersion)
        .set(CstMemberPo::getImAccount, imAccount)
        .set(CstMemberPo::getImUsername, imUsername)
        .set(CstMemberPo::getImBoundAt, imBoundAt)
        .set(CstMemberPo::getUpdatedAt, LocalDateTime.now())
        // 姓名只有传了才写：传 null 是"本次不改姓名"，不是"把姓名清空"。
        .set(newNameCipher != null, CstMemberPo::getNameCipher, newNameCipher)
        .setSql("version = version + 1");
    return memberMapper.update(null, update) > 0;
  }

  @Override
  public boolean updateAttributes(Long id, LocalDateTime joinedAt, Long levelId, String status, Long accountId,
                                  int expectedVersion) {
    LambdaUpdateWrapper<CstMemberPo> update = Wrappers.<CstMemberPo>lambdaUpdate()
        .eq(CstMemberPo::getId, id)
        .eq(CstMemberPo::getVersion, expectedVersion)
        .set(joinedAt != null, CstMemberPo::getJoinedAt, joinedAt)
        .set(levelId != null, CstMemberPo::getLevelId, levelId)
        .set(status != null, CstMemberPo::getStatus, status)
        .set(accountId != null, CstMemberPo::getAccountId, accountId)
        .set(CstMemberPo::getUpdatedAt, LocalDateTime.now())
        .setSql("version = version + 1");
    return memberMapper.update(null, update) > 0;
  }

  @Override
  public boolean deleteById(Long id) {
    return memberMapper.deleteById(id) > 0;
  }

  private static Member toDomain(CstMemberPo po) {
    return new Member(po.getId(), po.getTenantId(), po.getOriginStoreId(), po.getAccountId(),
        po.getImAccount(), po.getImUsername(), po.getImBoundAt(), po.getMemberNo(), po.getNameCipher(),
        po.getPhoneCipher(), po.getPhoneDigest(), po.getLevelId(), po.getStatus(), po.getJoinedAt(),
        po.getExpiresAt(), po.getVersion());
  }
}
