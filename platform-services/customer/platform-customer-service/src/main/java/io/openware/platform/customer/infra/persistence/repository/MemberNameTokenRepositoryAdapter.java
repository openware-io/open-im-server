package io.openware.platform.customer.infra.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.platform.customer.domain.member.model.MemberNameToken;
import io.openware.platform.customer.domain.member.repository.MemberNameTokenRepository;
import io.openware.platform.customer.infra.persistence.mapper.MemberNameTokenMapper;
import io.openware.platform.customer.infra.persistence.po.CstMemberNameTokenPo;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

/**
 * {@link MemberNameTokenRepository} 的持久化适配器。
 *
 * <p>租户拦截器的两类口径（见端口说明）：请求路径显式传 tenantId 且不忽略拦截器；
 * 存量回填路径忽略拦截器并显式传 tenantId（定时任务线程没有租户上下文）。
 *
 * <p>{@link #replaceForMember} 的"先删后插 + 失败补偿"是<b>原实现的行为</b>，不是新加的加固：
 * 删完旧的、插新的过程中失败时必须把这名客户退回"零 token 行"状态，否则会留下半套索引，
 * 而回填扫描的判据是"没有 token 行"——半套索引会被误判为已完成，此后按姓名再也搜不到。
 */
@Slf4j
@Repository
public class MemberNameTokenRepositoryAdapter implements MemberNameTokenRepository {

  private final MemberNameTokenMapper nameTokenMapper;

  public MemberNameTokenRepositoryAdapter(MemberNameTokenMapper nameTokenMapper) {
    this.nameTokenMapper = nameTokenMapper;
  }

  @Override
  public List<MemberNameToken> findByMember(Long memberId) {
    if (memberId == null) {
      return List.of();
    }
    return nameTokenMapper.selectList(Wrappers.<CstMemberNameTokenPo>lambdaQuery()
            .eq(CstMemberNameTokenPo::getMemberId, memberId)
            .orderByAsc(CstMemberNameTokenPo::getId))
        .stream().map(MemberNameTokenRepositoryAdapter::toDomain).toList();
  }

  @Override
  public int deleteByMember(Long memberId) {
    if (memberId == null) {
      return 0;
    }
    return nameTokenMapper.delete(ownerWrapper(memberId));
  }

  @Override
  public void replaceForMember(Long memberId, Collection<MemberNameToken> tokens, LocalDateTime occurredAt) {
    if (memberId == null) {
      return;
    }
    nameTokenMapper.delete(ownerWrapper(memberId));
    if (tokens == null || tokens.isEmpty()) {
      return;
    }
    try {
      for (MemberNameToken token : tokens) {
        CstMemberNameTokenPo row = new CstMemberNameTokenPo();
        row.setTenantId(token.tenantId());
        row.setMemberId(memberId);
        row.setToken(token.token());
        row.setCreatedAt(occurredAt);
        // 唯一键 (tenant_id, member_id, token) 冲突必须抛出：吞掉它会让调用方以为索引已重建。
        nameTokenMapper.insert(row);
      }
    } catch (RuntimeException failure) {
      // 补偿删除：把这名客户退回「零 token 行」状态，让下一轮回填重新处理（而不是留下半套索引）。
      try {
        nameTokenMapper.delete(ownerWrapper(memberId));
      } catch (RuntimeException compensationFailure) {
        log.warn("补偿删除半套盲索引失败（下一轮回填会再次处理该客户）: memberId={}, cause={}",
            memberId, compensationFailure.getMessage());
      }
      throw failure;
    }
  }

  @Override
  public List<Long> findMemberIdsByAllTokens(long tenantId, Collection<String> tokens) {
    if (tokens == null || tokens.isEmpty()) {
      return List.of();
    }
    List<String> ordered = new ArrayList<>(tokens);
    List<Long> hits = nameTokenMapper.selectMemberIdsByAllTokens(tenantId, ordered, ordered.size());
    return hits == null ? List.of() : hits;
  }

  @Override
  public List<Long> listTenantIdsWithMembers() {
    List<Long> tenantIds = nameTokenMapper.selectTenantIdsWithMembers();
    return tenantIds == null ? List.of() : tenantIds.stream().filter(Objects::nonNull).toList();
  }

  @Override
  public List<Long> findMemberIdsMissingTokens(long tenantId, long afterId, int limit) {
    List<Long> memberIds = nameTokenMapper.selectMemberIdsMissingTokens(tenantId, afterId, limit);
    return memberIds == null ? List.of() : memberIds;
  }

  @Override
  public long countMembersMissingTokens(long tenantId) {
    return nameTokenMapper.countMembersMissingTokens(tenantId);
  }

  private static LambdaQueryWrapper<CstMemberNameTokenPo> ownerWrapper(Long memberId) {
    return Wrappers.<CstMemberNameTokenPo>lambdaQuery().eq(CstMemberNameTokenPo::getMemberId, memberId);
  }

  private static MemberNameToken toDomain(CstMemberNameTokenPo po) {
    return new MemberNameToken(po.getId(), po.getTenantId(), po.getMemberId(), po.getToken());
  }
}
