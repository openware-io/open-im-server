package io.openware.platform.tenant.application;

import io.openware.platform.tenant.domain.iam.model.Permission;
import io.openware.platform.tenant.domain.iam.model.PermissionCatalogSnapshot;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.repository.IamCatalogVersionRepository;
import io.openware.platform.tenant.domain.iam.repository.PermissionRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleGrantRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.infra.authorization.PermissionCatalogCache;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 平台权限目录矩阵查询。
 *
 * <p>一次读取同时取「权限字典 + 全局角色 + 角色授权」，避免前端按角色逐个拉取造成的 N+1，也保证
 * 列表与矩阵在同一快照内。缓存以数据库派生的目录版本为键：版本不变命中，版本变化后自然换键，
 * 多实例下不需要广播失效。
 *
 * <p>只读平台元数据：权限字典、全局角色（{@code tenant_id IS NULL}）与它们的授权关系，不属于
 * 任何租户；因此与既有 {@code GET /admin/iam/permissions} 一样不要求租户上下文。
 */
@Service
public class PermissionCatalogApplicationService {

  private static final Map<String, Integer> SCOPE_ORDER = Map.of(
      "PLATFORM", 0, "TENANT", 1, "ORGANIZATION", 2, "STORE", 3);

  /** 预置角色的业务顺序；自定义角色在同一作用域内排在预置之后并按编码。 */
  private static final Map<String, Integer> ROLE_RANK = Map.of(
      "platform.operator", 0,
      "tenant.owner", 0,
      "store.manager", 0,
      "store.cashier", 10,
      "store.finance", 20);

  private final PermissionRepository permissionRepository;
  private final RoleRepository roleRepository;
  private final RoleGrantRepository roleGrantRepository;
  private final IamCatalogVersionRepository versionRepository;
  private final PermissionCatalogCache cache;
  private final MeterRegistry meterRegistry;

  /** 同 JVM 内同版本并发未命中时只允许一个线程重建，其余线程共享结果，避免缓存击穿。 */
  private final ConcurrentHashMap<String, CompletableFuture<PermissionCatalogSnapshot>> inFlight =
      new ConcurrentHashMap<>();

  /** 单测/无指标环境使用；生产由 Spring 注入 MeterRegistry。 */
  public PermissionCatalogApplicationService(PermissionRepository permissionRepository,
                                             RoleRepository roleRepository,
                                             RoleGrantRepository roleGrantRepository,
                                             IamCatalogVersionRepository versionRepository,
                                             PermissionCatalogCache cache) {
    this(permissionRepository, roleRepository, roleGrantRepository, versionRepository, cache, null);
  }

  @Autowired
  public PermissionCatalogApplicationService(PermissionRepository permissionRepository,
                                             RoleRepository roleRepository,
                                             RoleGrantRepository roleGrantRepository,
                                             IamCatalogVersionRepository versionRepository,
                                             PermissionCatalogCache cache,
                                             MeterRegistry meterRegistry) {
    this.permissionRepository = permissionRepository;
    this.roleRepository = roleRepository;
    this.roleGrantRepository = roleGrantRepository;
    this.versionRepository = versionRepository;
    this.cache = cache;
    this.meterRegistry = meterRegistry;
  }

  /**
   * 读取目录快照。
   *
   * @param refresh {@code true} 时跳过缓存读取（仍会写回最新版本），用于页面手动刷新
   */
  @Transactional(readOnly = true)
  public Result snapshot(boolean refresh) {
    String version = versionRepository.currentVersion();
    if (!refresh) {
      PermissionCatalogSnapshot cached = cache.get(version);
      if (cached != null) {
        recordCache("hit");
        return new Result(cached, true);
      }
      recordCache("miss");
      return new Result(rebuildSingleFlight(version), false);
    }
    recordCache("refresh");
    return new Result(rebuild(version), false);
  }

  /** 缓存未命中时，同版本只让一个线程构建；其余线程等待同一结果。 */
  private PermissionCatalogSnapshot rebuildSingleFlight(String version) {
    return rebuildSingleFlight(version, () -> build(version));
  }

  private PermissionCatalogSnapshot rebuildSingleFlight(String cacheKey,
                                                         Supplier<PermissionCatalogSnapshot> builder) {
    CompletableFuture<PermissionCatalogSnapshot> mine = new CompletableFuture<>();
    CompletableFuture<PermissionCatalogSnapshot> existing = inFlight.putIfAbsent(cacheKey, mine);
    if (existing != null) {
      recordCache("join");
      try {
        return existing.join();
      } catch (CompletionException error) {
        Throwable cause = error.getCause();
        throw cause instanceof RuntimeException runtime ? runtime : error;
      }
    }
    try {
      recordCache("rebuild");
      PermissionCatalogSnapshot built = builder.get();
      cache.put(cacheKey, built);
      mine.complete(built);
      return built;
    } catch (RuntimeException error) {
      mine.completeExceptionally(error);
      throw error;
    } finally {
      inFlight.remove(cacheKey, mine);
    }
  }

  private void recordCache(String result) {
    if (meterRegistry != null) {
      meterRegistry.counter("permission.catalog.matrix.cache", "result", result).increment();
    }
  }

  private PermissionCatalogSnapshot rebuild(String version) {
    return rebuild(version, () -> build(version));
  }

  private PermissionCatalogSnapshot rebuild(String cacheKey,
                                            Supplier<PermissionCatalogSnapshot> builder) {
    PermissionCatalogSnapshot built = builder.get();
    cache.put(cacheKey, built);
    return built;
  }

  /** 当前目录版本；供前端轻量校验数据是否更新。 */
  @Transactional(readOnly = true)
  public String currentVersion() {
    return versionRepository.currentVersion();
  }

  /**
   * 租户角色权限矩阵：全局预置角色 + 本租户自定义角色；缓存键包含 tenantId，避免跨租户碰撞。
   */
  @Transactional(readOnly = true)
  public Result tenantSnapshot(Long tenantId, boolean refresh) {
    String version = versionRepository.tenantVersion(tenantId);
    String cacheKey = "tenant:" + tenantId + ":" + version;
    if (!refresh) {
      PermissionCatalogSnapshot cached = cache.get(cacheKey);
      if (cached != null) {
        recordCache("hit");
        return new Result(cached, true);
      }
      recordCache("miss");
      return new Result(rebuildSingleFlight(cacheKey,
          () -> build(version, roleRepository.listAssignableByTenant(tenantId))), false);
    }
    recordCache("refresh");
    return new Result(rebuild(cacheKey,
        () -> build(version, roleRepository.listAssignableByTenant(tenantId))), false);
  }

  @Transactional(readOnly = true)
  public String currentTenantVersion(Long tenantId) {
    return versionRepository.tenantVersion(tenantId);
  }

  private PermissionCatalogSnapshot build(String version) {
    return build(version, roleRepository.listGlobalRoles());
  }

  private PermissionCatalogSnapshot build(String version, List<Role> roleDefinitions) {
    List<Permission> permissions = permissionRepository.listActive();
    List<Role> roles = roleDefinitions.stream()
        .sorted(Comparator
            .comparingInt((Role role) -> SCOPE_ORDER.getOrDefault(role.scopeLevel(), 4))
            .thenComparingInt(role -> ROLE_RANK.getOrDefault(role.code(), 100))
            .thenComparing(Role::code))
        .toList();
    List<Long> roleIds = roles.stream().map(Role::id).toList();
    Map<Long, List<Long>> grants = roleGrantRepository.findPermissionIdsByRoles(roleIds);
    return new PermissionCatalogSnapshot(permissions, roles, grants, version, Instant.now().toString());
  }

  /** 查询结果：{@code cached} 表示是否直接命中缓存（用于前端展示数据新鲜度）。 */
  public record Result(PermissionCatalogSnapshot snapshot, boolean cached) {
  }
}
