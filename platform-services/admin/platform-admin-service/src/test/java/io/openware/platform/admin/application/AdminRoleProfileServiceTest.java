package io.openware.platform.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.domain.role.repository.AdminRoleProfileRepository;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 平台角色工作区解析：超级管理员不得被伪装成 IAM platform.operator。 */
class AdminRoleProfileServiceTest {

  private AdminRoleProfileRepository profiles;
  private TenantIamDomainClient tenantIamClient;
  private AdminRoleProfileService service;

  @BeforeEach
  void setUp() {
    profiles = mock(AdminRoleProfileRepository.class);
    tenantIamClient = mock(TenantIamDomainClient.class);
    service = new AdminRoleProfileService(profiles, tenantIamClient);
  }

  @Test
  void superAdminUsesFixedPlatformWorkspaceWithoutIamRole() {
    AdminRoleProfileService.AdminWorkspace workspace = service.resolve(1L, AdminRole.SUPER_ADMIN);

    assertEquals(List.of(), workspace.roles());
    assertEquals(List.of("PLATFORM"), workspace.workspaces());
    assertEquals("PLATFORM", workspace.defaultWorkspace());
    assertEquals("/admin/platform/tenants", workspace.landing());
    verifyNoInteractions(profiles, tenantIamClient);
  }
}
