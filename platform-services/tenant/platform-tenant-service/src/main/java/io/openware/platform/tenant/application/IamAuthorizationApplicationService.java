package io.openware.platform.tenant.application;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.infrastructure.tenant.TenantContextHolder;
import io.openware.platform.tenant.domain.iam.model.MaskingRole;
import io.openware.platform.tenant.domain.iam.model.Permission;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.model.UserRoleBinding;
import io.openware.platform.tenant.domain.iam.repository.IamCatalogVersionRepository;
import io.openware.platform.tenant.domain.iam.repository.PermissionRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleGrantRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.domain.iam.repository.UserRoleRepository;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IAM 授权编排（角色-权限授予、账号-角色绑定）。
 *
 * <p>领域规则不在这里：作用域白名单判定在 {@link Permission#grantableTo}，作用域与归属字段的形状约束在
 * {@link UserRoleBinding#scopeShapeValid}。本服务只做"取事实 → 交给领域判定 → 落库 → 失效缓存"的编排，
 * 这正是应用服务应有的职责边界（standards/10）。
 */
@Service
public class IamAuthorizationApplicationService {

  /** 授权目标作用域非法（越出权限白名单，或作用域与归属字段不自洽）。 */
  private static final String CODE_INVALID_GRANT_SCOPE = "INVALID_GRANT_SCOPE";

  private static final String CODE_ROLE_NOT_FOUND = "ROLE_NOT_FOUND";

  private static final String CODE_PERMISSION_NOT_FOUND = "PERMISSION_NOT_FOUND";

  /** 角色权限集合已被其他管理员修改（乐观并发校验失败）。 */
  private static final String CODE_PERMISSION_VERSION_CONFLICT = "PERMISSION_VERSION_CONFLICT";

  private final RoleRepository roleRepository;
  private final PermissionRepository permissionRepository;
  private final RoleGrantRepository roleGrantRepository;
  private final UserRoleRepository userRoleRepository;
  private final PermissionSnapshotProvider permissionSnapshotProvider;
  private final IamCatalogVersionRepository catalogVersionRepository;
  private final AuditClient auditClient;

  /** 兼容既有单测/装配：不传审计客户端时使用关闭态客户端。 */
  public IamAuthorizationApplicationService(RoleRepository roleRepository,
                                            PermissionRepository permissionRepository,
                                            RoleGrantRepository roleGrantRepository,
                                            UserRoleRepository userRoleRepository,
                                            PermissionSnapshotProvider permissionSnapshotProvider,
                                            IamCatalogVersionRepository catalogVersionRepository) {
    this(roleRepository, permissionRepository, roleGrantRepository, userRoleRepository,
        permissionSnapshotProvider, catalogVersionRepository, AuditClient.disabled());
  }

  @Autowired
  public IamAuthorizationApplicationService(RoleRepository roleRepository,
                                            PermissionRepository permissionRepository,
                                            RoleGrantRepository roleGrantRepository,
                                            UserRoleRepository userRoleRepository,
                                            PermissionSnapshotProvider permissionSnapshotProvider,
                                            IamCatalogVersionRepository catalogVersionRepository,
                                            AuditClient auditClient) {
    this.roleRepository = roleRepository;
    this.permissionRepository = permissionRepository;
    this.roleGrantRepository = roleGrantRepository;
    this.userRoleRepository = userRoleRepository;
    this.permissionSnapshotProvider = permissionSnapshotProvider;
    this.catalogVersionRepository = catalogVersionRepository;
    this.auditClient = auditClient;
  }

  /** 权限目录（平台级）。 */
  @Transactional(readOnly = true)
  public List<Permission> listPermissions() {
    return permissionRepository.listActive();
  }

  /** 脱敏角色投影（角色 + 可见 PII 权限）。 */
  @Transactional(readOnly = true)
  public List<MaskingRole> listMaskingRoles() {
    return roleRepository.listMaskingRoles();
  }

  /** 某租户的可用角色。 */
  @Transactional(readOnly = true)
  public List<Role> listRoles(Long tenantId) {
    return roleRepository.listActiveByTenant(tenantId);
  }

  /** 角色权限计数（批量，避免角色列表按角色逐个查询）。 */
  @Transactional(readOnly = true)
  public Map<Long, Long> permissionCounts(List<Long> roleIds) {
    return roleGrantRepository.countByRoleIds(roleIds);
  }

  /** 某租户可分配的角色：全局预置角色 + 租户自定义角色，不含平台角色。 */
  @Transactional(readOnly = true)
  public List<Role> listAssignableRoles(Long tenantId) {
    return roleRepository.listAssignableByTenant(tenantId);
  }

  /** 平台角色目录，仅平台上下文使用。 */
  @Transactional(readOnly = true)
  public List<Role> listPlatformRoles() {
    return roleRepository.listPlatformRoles();
  }

  /** 账号的 iam_user_role 绑定 id 列表。 */
  @Transactional(readOnly = true)
  public List<Long> listUserRoleIds(Long accountId) {
    return userRoleRepository.findIdsByAccount(accountId);
  }

  /** 新建平台运营角色并留痕。 */
  @Transactional
  public Role createPlatformRole(String code, String name) {
    String normalizedCode = code == null ? "" : code.trim();
    String normalizedName = name == null ? "" : name.trim();
    if (!normalizedCode.matches("[a-z][a-z0-9_.-]{2,63}")) {
      throw new ApiException(400, "ROLE_CODE_INVALID", "角色编码应为小写字母开头，3-64 位");
    }
    if (normalizedName.isEmpty() || normalizedName.length() > 64) {
      throw new ApiException(400, "ROLE_NAME_INVALID", "角色名称不能为空且不超过 64 位");
    }
    Role role = roleRepository.insert(Role.createPlatform(normalizedCode, normalizedName));
    auditClient.recordAsync(AuditClient.AuditRecord.builder()
        .tenantId(0L)
        .action("iam.platform_role.create")
        .resourceType("role")
        .resourceId(String.valueOf(role.id()))
        .resourceName(role.name())
        .idempotencyKey("platform-role:" + role.id())
        .detailJson("{\"scopeLevel\":\"PLATFORM\"}")
        .build());
    return role;
  }

  /** 新建租户自定义角色并留痕；scopeLevel 决定该角色只能获得 TENANT 或 STORE 权限。 */
  @Transactional
  public Role createRole(Long tenantId, String code, String name, String roleType, String scopeLevel) {
    String normalizedCode = code == null ? "" : code.trim();
    String normalizedName = name == null ? "" : name.trim();
    if (!normalizedCode.matches("[a-z][a-z0-9_.-]{2,63}")) {
      throw new ApiException(400, "ROLE_CODE_INVALID", "角色编码应为小写字母开头，3-64 位");
    }
    if (normalizedName.isEmpty() || normalizedName.length() > 64) {
      throw new ApiException(400, "ROLE_NAME_INVALID", "角色名称不能为空且不超过 64 位");
    }
    Role role = roleRepository.insert(Role.create(tenantId, normalizedCode, normalizedName, roleType, scopeLevel));
    auditClient.recordAsync(AuditClient.AuditRecord.builder()
        .tenantId(tenantId)
        .action("iam.role.create")
        .resourceType("role")
        .resourceId(String.valueOf(role.id()))
        .resourceName(role.name())
        .idempotencyKey("role:" + role.id())
        .detailJson("{\"tenantId\":" + tenantId + ",\"roleType\":\"" + role.roleType()
            + "\",\"scopeLevel\":\"" + role.scopeLevel() + "\"}")
        .build());
    return role;
  }

  /**
   * 用给定权限集合整体替换角色的授予关系。
   *
   * <p>校验顺序是先"权限是否都存在"再"是否都可授予"：请求了不存在的权限标识必须拒绝，
   * 不能静默忽略——否则前端以为授予成功，实际少授了。
   *
   * <p>并发控制：先对角色行加锁，再做版本校验，最后替换授权。这样“版本校验通过 → 替换”之间
   * 不会被另一个管理员的写事务插入；{@code expectedVersion} 为空时保留旧调用方的无版本语义。
   */
  @Transactional
  public Role assignPermissions(Long roleId, List<Long> permissionIds) {
    return assignPermissions(roleId, permissionIds, null);
  }

  /** 带乐观并发校验的权限集合替换；{@code expectedVersion} 来自角色权限快照接口。 */
  @Transactional
  public Role assignPermissions(Long roleId, List<Long> permissionIds, String expectedVersion) {
    Role role = requireActiveRoleForUpdate(roleId);
    assertTenantCanWriteRole(role);
    assertPermissionVersion(roleId, expectedVersion);
    List<Long> ids = permissionIds == null ? List.of() : permissionIds;
    if (!ids.isEmpty()) {
      List<Permission> permissions = permissionRepository.findByIds(ids);
      if (permissions.size() != ids.size()
          || permissions.stream().anyMatch(permission -> !permission.grantableTo(role.scopeLevel()))) {
        throw invalidGrantScope();
      }
    }
    roleGrantRepository.replaceRolePermissions(roleId, ids);
    // 角色-权限变更影响权限快照：逐出缓存
    permissionSnapshotProvider.evictAll();
    auditClient.recordAsync(AuditClient.AuditRecord.of(null, null, "iam.role.permissions.assign", "role",
        String.valueOf(roleId), null, "role-perm:" + roleId,
        "{\"permissionIds\":\"" + ids + "\"}"));
    return role;
  }

  /**
   * 切换单个权限的授予状态。
   *
   * <p>{@code granted} 为真时授予、为假时撤销，两者都幂等。
   */
  @Transactional
  public Role togglePermission(Long roleId, String permissionCode, boolean granted) {
    return togglePermission(roleId, permissionCode, granted, null);
  }

  /** 带乐观并发校验的单权限开关。 */
  @Transactional
  public Role togglePermission(Long roleId, String permissionCode, boolean granted, String expectedVersion) {
    Role role = requireActiveRoleForUpdate(roleId);
    Permission permission = permissionRepository.findByCode(permissionCode)
        .orElseThrow(() -> new ApiException(404, CODE_PERMISSION_NOT_FOUND, "权限不存在"));
    if (!permission.grantableTo(role.scopeLevel())) {
      throw invalidGrantScope();
    }
    assertTenantCanWriteRole(role);
    assertPermissionVersion(roleId, expectedVersion);
    if (granted) {
      roleGrantRepository.grant(roleId, permission.id());
    } else {
      roleGrantRepository.revoke(roleId, permission.id());
    }
    permissionSnapshotProvider.evictAll();
    auditClient.recordAsync(AuditClient.AuditRecord.of(null, null, "iam.role.permission.toggle", "role",
        String.valueOf(roleId), null, "role-perm-toggle:" + roleId + ":" + permissionCode,
        "permissionCode=" + permissionCode + ",granted=" + granted));
    return role;
  }

  /**
   * 角色权限快照：已授予编码 + 版本，供编辑弹窗做乐观并发校验。
   */
  @Transactional(readOnly = true)
  public RolePermissionSnapshot rolePermissionSnapshot(Long roleId) {
    List<String> codes = listRolePermissionCodes(roleId);
    return new RolePermissionSnapshot(codes, catalogVersionRepository.rolePermissionVersion(roleId));
  }

  /** 角色已授予的权限编码（按标识集合解析成对人可读的编码）。 */
  @Transactional(readOnly = true)
  public List<String> listRolePermissionCodes(Long roleId) {
    Role role = requireActiveRole(roleId);
    assertCanReadRole(role);
    List<Long> ids = roleGrantRepository.findPermissionIdsByRole(roleId);
    if (ids.isEmpty()) {
      return List.of();
    }
    return permissionRepository.findByIds(ids).stream().map(Permission::code).toList();
  }

  /**
   * 给账号绑定角色。
   *
   * <p>三重边界：角色必须存在且可用、绑定的作用域类型必须与角色作用域层级一致、
   * 归属字段必须与该作用域自洽（平台级不得挂租户等）。
   */
  /** 迁移期兼容：新写路径走 RoleBindingApplicationService。 */
  @Transactional
  public UserRoleBinding assignUserRole(Long accountId, Long tenantId, Long organizationId, Long storeId,
                                        Long roleId, String scopeType) {
    throw new ApiException(410, "LEGACY_ROLE_WRITE_DISABLED",
        "旧角色绑定写路径已停用，请使用成员角色绑定接口");
  }

  /**
   * 撤销账号-角色绑定（软删 status → REVOKED），返回被撤销绑定归属的账号。
   *
   * <p>账号要返回给调用方：它既是审计详情的内容，也是快照逐出的依据——撤销只逐出该账号的缓存，
   * 而不是像角色-权限变更那样全量逐出（那会把所有账号的快照一并打掉）。
   *
   * @return 归属账号；绑定不存在时为空，调用方据此返回 404 而不是静默成功
   */
  /** 迁移期兼容：新写路径走 RoleBindingApplicationService。 */
  @Transactional
  public Long revokeUserRole(Long userRoleId) {
    throw new ApiException(410, "LEGACY_ROLE_WRITE_DISABLED",
        "旧角色绑定写路径已停用，请使用成员角色绑定接口");
  }

  /** 从角色页移除某人员：按账号 + 角色撤销，租户只能操作自己范围内的角色。 */
  @Transactional
  public void revokeRoleFromAccount(Long roleId, Long accountId) {
    throw new ApiException(410, "LEGACY_ROLE_WRITE_DISABLED",
        "旧角色绑定写路径已停用，请使用成员角色绑定接口");
  }

  /** 调整已有人员的作用域，不撤销角色；只允许租户作用域内的角色。 */
  /** 迁移期兼容：新写路径走 RoleBindingApplicationService。 */
  @Transactional
  public void updateRoleAssignmentScope(Long roleId, Long accountId, Long organizationId, Long storeId,
                                        String scopeType) {
    throw new ApiException(410, "LEGACY_ROLE_WRITE_DISABLED",
        "旧角色绑定写路径已停用，请使用成员角色绑定接口");
  }

  private Role requireActiveRole(Long roleId) {
    Role role = roleRepository.findById(roleId).orElse(null);
    if (role == null || !role.active()) {
      throw new ApiException(404, CODE_ROLE_NOT_FOUND, "角色不存在");
    }
    return role;
  }

  private Role requireActiveRoleForUpdate(Long roleId) {
    Role role = roleRepository.findByIdForUpdate(roleId).orElse(null);
    if (role == null || !role.active()) {
      throw new ApiException(404, CODE_ROLE_NOT_FOUND, "角色不存在");
    }
    return role;
  }

  /**
   * 乐观并发校验：只有调用方明确带版本时才校验；空版本保留旧接口的无条件替换语义。
   *
   * <p>调用点必须在角色行锁之后执行，否则校验和替换之间仍可能被并发写穿透。
   */
  private void assertPermissionVersion(Long roleId, String expectedVersion) {
    if (expectedVersion == null || expectedVersion.isBlank()) {
      return;
    }
    String current = catalogVersionRepository.rolePermissionVersion(roleId);
    if (!expectedVersion.equals(current)) {
      throw new ApiException(409, CODE_PERMISSION_VERSION_CONFLICT,
          "角色权限已被其他管理员修改，请刷新后重试");
    }
  }

  /** 角色权限快照：编码列表 + 版本。 */
  public record RolePermissionSnapshot(List<String> permissionCodes, String version) {
  }

  private void assertTenantCanWriteRole(Role role) {
    TenantContext context = TenantContextHolder.get();
    if (context == null) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少上下文");
    }
    if (context.platformScope()) {
      if (role.tenantId() == null && role.hasScopeLevel(Role.SCOPE_PLATFORM)) {
        return;
      }
      throw new ApiException(403, "ROLE_SCOPE_FORBIDDEN", "不能修改租户角色");
    }
    if (context.tenantId() <= 0) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少租户上下文");
    }
    if (role.tenantId() == null) {
      throw new ApiException(403, "PRESET_ROLE_READONLY", "预置角色不可修改，请复制为租户自定义角色");
    }
    if (!role.tenantId().equals(context.tenantId())) {
      throw new ApiException(403, "ROLE_TENANT_MISMATCH", "不能修改其他租户的角色");
    }
  }

  /**
   * 读取角色权限的边界：平台上下文可读全局角色；租户上下文可读全局预置角色 + 本租户角色；
   * 禁止跨租户按 roleId 读取权限集合。
   */
  private void assertCanReadRole(Role role) {
    TenantContext context = TenantContextHolder.get();
    if (context == null) {
      throw new ApiException(401, "SAAS_CONTEXT_REQUIRED", "缺少上下文");
    }
    if (context.platformScope()) {
      if (role.tenantId() == null) {
        return;
      }
      throw new ApiException(403, "ROLE_SCOPE_FORBIDDEN", "不能读取租户角色");
    }
    if (role.tenantId() == null) {
      return;
    }
    if (!role.tenantId().equals(context.tenantId())) {
      throw new ApiException(403, "ROLE_TENANT_MISMATCH", "不能读取其他租户的角色");
    }
  }

  private static ApiException invalidGrantScope() {
    return new ApiException(400, CODE_INVALID_GRANT_SCOPE, "权限或角色不允许授予目标作用域");
  }
}
