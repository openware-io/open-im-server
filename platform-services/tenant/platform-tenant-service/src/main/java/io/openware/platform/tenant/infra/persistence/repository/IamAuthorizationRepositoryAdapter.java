package io.openware.platform.tenant.infra.persistence.repository;

import io.openware.platform.tenant.domain.authorization.ConsumerAuthorization;
import io.openware.platform.tenant.domain.authorization.IamContext;
import io.openware.platform.tenant.domain.authorization.RoleBinding;
import io.openware.platform.tenant.domain.authorization.RoleScope;
import io.openware.platform.tenant.domain.authorization.repository.IamAuthorizationRepository;
import io.openware.platform.tenant.domain.iam.model.MaskingRole;
import io.openware.platform.tenant.infra.persistence.mapper.IamSnapshotMapper;
import io.openware.platform.tenant.infra.persistence.row.IamContextRow;
import io.openware.platform.tenant.infra.persistence.row.IamRoleBindingRow;
import io.openware.platform.tenant.infra.persistence.row.IamRoleRow;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * {@link IamAuthorizationRepository} 的持久化适配器。
 *
 * <p><b>本类是该批查询里行对象与领域投影的唯一翻译点</b>：向上只返回领域类型，下方只认 mapper 的行对象。
 * 这样"跨表装配查询"的实现细节不会渗到应用层，也就不会再出现应用层 import 持久化行的情形。
 *
 * <p>所有查询都走 {@link IamSnapshotMapper} 的专用语句（已带 {@code @InterceptIgnore(tenantLine)}），
 * 不用 lambda Wrapper 重写——那些语句的跨租户语义正是本端口的契约要求。
 */
@Repository
public class IamAuthorizationRepositoryAdapter implements IamAuthorizationRepository {

  private final IamSnapshotMapper mapper;

  public IamAuthorizationRepositoryAdapter(IamSnapshotMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Optional<ConsumerAuthorization> findConsumerAuthorization(String appId, Long accountId) {
    var row = mapper.selectConsumerApplication(appId, accountId);
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(new ConsumerAuthorization(row.getAppId(), row.getTenantId(), row.getTenantName(),
        row.getOrganizationId(), row.getOrganizationName(), row.getStoreId(), row.getStoreName(),
        row.getPermissionsJson(), row.getAuthorizationVersion()));
  }

  @Override
  public List<String> findPermissionCodes(Long accountId, Long tenantId, Long organizationId, Long storeId) {
    List<String> codes = mapper.selectPermissionCodes(accountId, tenantId, organizationId, storeId);
    return codes == null ? List.of() : codes;
  }

  @Override
  public Optional<Integer> findAuthorizationVersion(Long accountId, Long tenantId, Long organizationId,
                                                    Long storeId) {
    return Optional.ofNullable(mapper.selectAuthorizationVersion(accountId, tenantId, organizationId, storeId));
  }

  @Override
  public boolean grantConsumerAuthorization(String appId, Long accountId, String scope) {
    // 行数语义反直觉：1 = 首次插入，2 = ON DUPLICATE KEY UPDATE，两者都表示生效；仅 0 表示应用不可用。
    // 在这里收敛成布尔，避免调用方去理解 MySQL 的行数约定。
    return mapper.grantConsumerAuthorization(appId, accountId, scope) > 0;
  }

  @Override
  public List<MaskingRole> findMaskingRoles() {
    return mapper.selectMaskingRoles().stream()
        .map(row -> new MaskingRole(row.getRoleId(), row.getCode(), row.getName(),
            row.getPiiView() != null && row.getPiiView()))
        .toList();
  }

  @Override
  public List<RoleBinding> findRoleBindings(Long accountId) {
    List<IamRoleBindingRow> rows =
        mapper.selectRoleBindings(accountId);
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream()
        .map(row -> new RoleBinding(row.getRoleId(), row.getTenantId(), row.getOrganizationId(),
            row.getStoreId(), row.getRoleCode(), row.getRoleName(), row.getScopeType(), row.getTenantName(),
            row.getStoreName(), row.getStatus()))
        .toList();
  }

  @Override
  public List<Long> findUserRoleIds(Long accountId) {
    List<Long> ids = mapper.selectUserRoleIds(accountId);
    return ids == null ? List.of() : ids;
  }

  @Override
  public Optional<Long> findAccountIdByUserRole(Long userRoleId) {
    return Optional.ofNullable(mapper.selectAccountIdByUserRole(userRoleId));
  }

  @Override
  public boolean revokeUserRole(Long userRoleId) {
    return mapper.revokeUserRole(userRoleId) > 0;
  }

  @Override
  public int countPlatformRoles(Long accountId) {
    return mapper.countPlatformRoles(accountId);
  }

  @Override
  public List<IamContext> findContexts(Long accountId) {
    List<IamContextRow> rows = mapper.selectContexts(accountId);
    return rows == null ? List.of() : rows.stream().map(IamAuthorizationRepositoryAdapter::toContext).toList();
  }

  @Override
  public List<RoleScope> findRoleScopes(Long accountId) {
    List<IamRoleRow> rows = mapper.selectRoles(accountId);
    if (rows == null) {
      return List.of();
    }
    return rows.stream()
        .map(row -> new RoleScope(row.getTenantId(), row.getOrganizationId(), row.getStoreId(),
            row.getRoleCode()))
        .toList();
  }

  @Override
  public List<IamContext> findAllTenantContexts() {
    return mapper.selectAllTenantContexts().stream()
        .map(IamAuthorizationRepositoryAdapter::toContext).toList();
  }

  @Override
  public List<IamContext> findActiveStores(Long tenantId, Long organizationId) {
    List<IamContextRow> rows =
        mapper.selectActiveStores(tenantId, organizationId);
    return rows == null ? List.of() : rows.stream().map(IamAuthorizationRepositoryAdapter::toContext).toList();
  }

  private static IamContext toContext(IamContextRow row) {
    return new IamContext(row.getTenantId(), row.getTenantName(), row.getOrganizationId(),
        row.getOrganizationName(), row.getStoreId(), row.getStoreName(), row.getScopeType(),
        row.getBusinessType(), row.getTimezone());
  }
}
