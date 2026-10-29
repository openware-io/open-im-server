package io.openware.platform.tenant.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openware.platform.tenant.domain.iam.model.Permission;
import io.openware.platform.tenant.domain.iam.model.PermissionCatalogSnapshot;
import io.openware.platform.tenant.domain.iam.model.Role;
import io.openware.platform.tenant.domain.iam.repository.IamCatalogVersionRepository;
import io.openware.platform.tenant.domain.iam.repository.PermissionRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleGrantRepository;
import io.openware.platform.tenant.domain.iam.repository.RoleRepository;
import io.openware.platform.tenant.infra.authorization.PermissionCatalogCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 权限目录矩阵查询：同一快照、全局角色排序、版本缓存命中与 refresh 绕过。 */
class PermissionCatalogApplicationServiceTest {

  private final PermissionRepository permissions = mock(PermissionRepository.class);
  private final RoleRepository roles = mock(RoleRepository.class);
  private final RoleGrantRepository grants = mock(RoleGrantRepository.class);
  private final IamCatalogVersionRepository versions = mock(IamCatalogVersionRepository.class);
  private final PermissionCatalogCache cache = mock(PermissionCatalogCache.class);

  private final PermissionCatalogApplicationService service = new PermissionCatalogApplicationService(
      permissions, roles, grants, versions, cache);

  @Test
  void buildsSnapshotAndOrdersGlobalRolesByScopeThenCode() {
    when(versions.currentVersion()).thenReturn("v1");
    when(permissions.listActive()).thenReturn(List.of(permission(1L, "a.b")));
    when(roles.listGlobalRoles()).thenReturn(List.of(
        role(3L, "store.manager", "STORE"),
        role(1L, "platform.operator", "PLATFORM"),
        role(2L, "tenant.owner", "TENANT")));
    when(grants.findPermissionIdsByRoles(List.of(1L, 2L, 3L))).thenReturn(Map.of(2L, List.of(1L)));

    PermissionCatalogApplicationService.Result result = service.snapshot(false);

    assertFalse(result.cached());
    assertEquals("v1", result.snapshot().version());
    assertEquals(List.of(1L, 2L, 3L), result.snapshot().roles().stream().map(Role::id).toList());
    assertEquals(Map.of(2L, List.of(1L)), result.snapshot().rolePermissionIds());
    ArgumentCaptor<PermissionCatalogSnapshot> cached = ArgumentCaptor.forClass(PermissionCatalogSnapshot.class);
    verify(cache).put(org.mockito.ArgumentMatchers.eq("v1"), cached.capture());
    assertSame(result.snapshot(), cached.getValue());
  }

  @Test
  void returnsCachedSnapshotWithoutHittingFacts() {
    PermissionCatalogSnapshot cached = snapshot("v1");
    when(versions.currentVersion()).thenReturn("v1");
    when(cache.get("v1")).thenReturn(cached);

    PermissionCatalogApplicationService.Result result = service.snapshot(false);

    assertTrue(result.cached());
    assertSame(cached, result.snapshot());
    verifyNoInteractions(permissions, roles, grants);
    verify(cache, never()).put(org.mockito.ArgumentMatchers.anyString(),
        org.mockito.ArgumentMatchers.any(PermissionCatalogSnapshot.class));
  }

  @Test
  void refreshBypassesCacheAndRebuilds() {
    PermissionCatalogSnapshot cached = snapshot("v1");
    when(versions.currentVersion()).thenReturn("v1");
    when(cache.get("v1")).thenReturn(cached);
    when(permissions.listActive()).thenReturn(List.of(permission(1L, "a.b")));
    when(roles.listGlobalRoles()).thenReturn(List.of(role(2L, "tenant.owner", "TENANT")));
    when(grants.findPermissionIdsByRoles(List.of(2L))).thenReturn(Map.of());

    PermissionCatalogApplicationService.Result result = service.snapshot(true);

    assertFalse(result.cached());
    assertEquals("v1", result.snapshot().version());
    verify(cache).put(org.mockito.ArgumentMatchers.eq("v1"),
        org.mockito.ArgumentMatchers.any(PermissionCatalogSnapshot.class));
    verify(permissions).listActive();
    verify(roles).listGlobalRoles();
  }

  @Test
  void concurrentCacheMissRebuildsOnlyOnce() throws Exception {
    int threads = 8;
    when(versions.currentVersion()).thenReturn("v1");
    CountDownLatch allMissed = new CountDownLatch(threads);
    CountDownLatch buildStarted = new CountDownLatch(1);
    CountDownLatch releaseBuild = new CountDownLatch(1);
    AtomicInteger builds = new AtomicInteger();
    when(cache.get("v1")).thenAnswer(invocation -> {
      allMissed.countDown();
      if (!allMissed.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("threads did not reach cache miss together");
      }
      return null;
    });
    when(permissions.listActive()).thenAnswer(invocation -> {
      builds.incrementAndGet();
      buildStarted.countDown();
      if (!releaseBuild.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("build release timed out");
      }
      return List.of(permission(1L, "a.b"));
    });
    when(roles.listGlobalRoles()).thenReturn(List.of(role(2L, "tenant.owner", "TENANT")));
    when(grants.findPermissionIdsByRoles(List.of(2L))).thenReturn(Map.of());

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<PermissionCatalogApplicationService.Result>> futures = new ArrayList<>();
      for (int index = 0; index < threads; index++) {
        futures.add(pool.submit(() -> service.snapshot(false)));
      }
      assertTrue(buildStarted.await(5, TimeUnit.SECONDS));
      Thread.sleep(100);
      releaseBuild.countDown();
      for (Future<PermissionCatalogApplicationService.Result> future : futures) {
        assertEquals("v1", future.get(5, TimeUnit.SECONDS).snapshot().version());
      }
      assertEquals(1, builds.get());
    } finally {
      releaseBuild.countDown();
      pool.shutdownNow();
    }
  }

  private static Permission permission(long id, String code) {
    return new Permission(id, code, "tenant", "store", "manage", code, "TENANT", "core",
        "TENANT", "store", "ACTIVE");
  }

  private static Role role(long id, String code, String scope) {
    return new Role(id, null, code, code, "PRESET", scope, null, null, "ACTIVE");
  }

  private static PermissionCatalogSnapshot snapshot(String version) {
    return new PermissionCatalogSnapshot(List.of(permission(1L, "a.b")),
        List.of(role(2L, "tenant.owner", "TENANT")), Map.of(), version, "2026-10-29T00:00:00Z");
  }
}
