package io.openware.platform.customer.infra.persistence.repository;

import io.openware.platform.customer.domain.member.repository.MemberImBindingRepository;
import io.openware.platform.customer.infra.persistence.mapper.MemberMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * {@link MemberImBindingRepository} 的持久化适配器：把 {@code MemberMapper} 上的两条跨域只读查询
 * （账号类型、IM 关联状态）翻译成领域类型。
 *
 * <p>两条查询都在 Mapper 上标了 {@code @InterceptorIgnore(tenantLine = "true")}：{@code idt_*} 与
 * {@code open_im.*} 与 {@code cst_*} 同库且不带 {@code tenant_id}，忽略租户行过滤是既有口径
 * （与 admin 报表跨域 join 同一范式）；目标行已按租户校验过，这里只做补齐/判定。
 */
@Repository
public class MemberImBindingRepositoryAdapter implements MemberImBindingRepository {

  private final MemberMapper memberMapper;

  public MemberImBindingRepositoryAdapter(MemberMapper memberMapper) {
    this.memberMapper = memberMapper;
  }

  @Override
  public String findImAccountByAccountId(Long accountId) {
    if (accountId == null) {
      return null;
    }
    String identifier = memberMapper.selectImAccountByAccountId(accountId);
    return identifier == null || identifier.isBlank() ? null : identifier;
  }

  @Override
  public String findImNicknameByAccountId(Long accountId) {
    if (accountId == null) {
      return null;
    }
    return memberMapper.selectImNicknameByAccountId(accountId);
  }

  @Override
  public Map<Long, String> findAccountTypes(List<Long> accountIds) {
    if (accountIds == null || accountIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, String> types = new LinkedHashMap<>();
    for (Map<String, Object> row : memberMapper.selectAccountTypes(accountIds)) {
      Object id = row.get("accountId");
      Object type = row.get("accountType");
      if (id == null || type == null) {
        continue;
      }
      types.put(((Number) id).longValue(), String.valueOf(type));
    }
    return types;
  }

  @Override
  public List<ImBinding> resolveBindings(List<Long> memberIds) {
    if (memberIds == null || memberIds.isEmpty()) {
      return List.of();
    }
    List<ImBinding> bindings = new ArrayList<>(memberIds.size());
    for (Map<String, Object> row : memberMapper.selectImAccounts(memberIds)) {
      Object memberId = row.get("memberId");
      if (memberId == null) {
        continue;
      }
      bindings.add(new ImBinding(
          ((Number) memberId).longValue(),
          asLong(row.get("accountId")),
          asLong(row.get("imUserId")),
          asString(row.get("imUsername")),
          asString(row.get("imNickname")),
          isDeletedFlag(row.get("deleted"))));
    }
    return bindings;
  }

  /** 跨域查询返回的计数/标识一律按 Number 处理：驱动不同（MySQL/H2）装箱类型不同。 */
  private static Long asLong(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }

  private static String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /** {@code deleted} 是 SQL 里算出来的 1/0：非 Number 一律按"未删除"处理（不伪造已删除）。 */
  private static boolean isDeletedFlag(Object value) {
    return value instanceof Number number && number.intValue() == 1;
  }
}
