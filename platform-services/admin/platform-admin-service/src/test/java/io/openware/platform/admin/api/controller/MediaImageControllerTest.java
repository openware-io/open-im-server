package io.openware.platform.admin.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.platform.admin.application.MediaImageApplicationService;
import io.openware.platform.admin.domain.model.AdminRole;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import io.openware.platform.admin.infra.security.AdminContext;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import io.openware.platform.admin.infra.security.AdminTenantContextTokenSigner;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 对外契约回归：multipart 字段 file、响应 {url, objectKey, contentType, size}、URL 形态 /api/v1/media-public/...；
 * 权限与上下文的 401/403 语义不变，媒体服务不可用时报 503。
 */
class MediaImageControllerTest {

  private final MediaImageApplicationService images = mock(MediaImageApplicationService.class);
  private final TenantIamDomainClient iam = mock(TenantIamDomainClient.class);
  private final AdminTenantContextTokenSigner tokens = mock(AdminTenantContextTokenSigner.class);
  private final MediaImageController controller = new MediaImageController(images, iam, tokens);

  @BeforeEach
  void setup() {
    AdminContextHolder.set(new AdminContext(1L, "admin", "admin", AdminRole.TENANT_ADMIN, 1L, "context"));
    when(tokens.verify("context")).thenReturn(new TenantContext(100L, null, null, 1L, 1));
    when(iam.permissions(1L, 100L, null, null)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
        1L, 100L, null, null, 1, List.of("product.manage")));
  }

  @AfterEach
  void cleanup() {
    AdminContextHolder.clear();
  }

  @Test
  void uploadsImageForContextWithProductManagePermission() {
    MockMultipartFile file = new MockMultipartFile("file", "cola.png", "image/png", bytes("png"));
    when(images.upload(eq(100L), eq(null), eq("image/png"), any(byte[].class))).thenReturn(
        new MediaImageApplicationService.UploadedImage(
            "/api/v1/media-public/open-media-public/saas/t100/202609/abc.png", "saas/t100/202609/abc.png", "image/png", 3L));

    MediaImageController.UploadedImageView view = controller.upload(file);

    assertEquals("/api/v1/media-public/open-media-public/saas/t100/202609/abc.png", view.url());
    assertEquals("saas/t100/202609/abc.png", view.objectKey());
    assertEquals("image/png", view.contentType());
    assertEquals(3L, view.size());
  }

  @Test
  void inventoryMaterialManagePermissionIsAlsoAccepted() {
    when(iam.permissions(1L, 100L, null, null)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
        1L, 100L, null, null, 1, List.of("inventory.material.manage")));
    when(images.upload(eq(100L), eq(null), eq("image/webp"), any(byte[].class))).thenReturn(
        new MediaImageApplicationService.UploadedImage(
            "/api/v1/media-public/open-media-public/saas/t100/202609/m.webp", "saas/t100/202609/m.webp", "image/webp", 4L));
    MockMultipartFile file = new MockMultipartFile("file", "m.webp", "image/webp", bytes("webp"));

    MediaImageController.UploadedImageView view = controller.upload(file);

    assertEquals("image/webp", view.contentType());
    verify(images).upload(eq(100L), eq(null), eq("image/webp"), any(byte[].class));
  }

  /** 包厢图片也走同一上传端点：只有 resource.manage 的运营账号必须能传图。 */
  @Test
  void resourceManagePermissionIsAlsoAccepted() {
    when(iam.permissions(1L, 100L, null, null)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
        1L, 100L, null, null, 1, List.of("resource.manage")));
    when(images.upload(eq(100L), eq(null), eq("image/jpeg"), any(byte[].class))).thenReturn(
        new MediaImageApplicationService.UploadedImage(
            "/api/v1/media-public/open-media-public/saas/t100/202609/room.jpg", "saas/t100/202609/room.jpg", "image/jpeg", 5L));
    MockMultipartFile file = new MockMultipartFile("file", "room.jpg", "image/jpeg", bytes("jpeg"));

    MediaImageController.UploadedImageView view = controller.upload(file);

    assertEquals("/api/v1/media-public/open-media-public/saas/t100/202609/room.jpg", view.url());
    assertEquals("saas/t100/202609/room.jpg", view.objectKey());
    assertEquals("image/jpeg", view.contentType());
    verify(images).upload(eq(100L), eq(null), eq("image/jpeg"), any(byte[].class));
  }

  @Test
  void missingContextIsRejectedWith401() {
    AdminContextHolder.set(new AdminContext(1L, "admin", "admin", AdminRole.TENANT_ADMIN, 1L, null));
    MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", bytes("png"));

    ApiException error = assertThrows(ApiException.class, () -> controller.upload(file));

    assertEquals(401, error.getStatus());
    assertEquals("SAAS_CONTEXT_REQUIRED", error.getCode());
    verifyNoInteractions(images);
  }

  @Test
  void missingManagePermissionIsRejectedWith403() {
    when(iam.permissions(1L, 100L, null, null)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
        1L, 100L, null, null, 1, List.of("product.view")));
    MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", bytes("png"));

    ApiException error = assertThrows(ApiException.class, () -> controller.upload(file));

    assertEquals(403, error.getStatus());
    assertEquals("PERMISSION_DENIED", error.getCode());
    verifyNoInteractions(images);
  }

  /** 商品/物料/包厢三类权限一个都没有（例如只有资源查看权限）必须 403，不能因为包厢上传被顺带放开。 */
  @Test
  void noneOfTheThreeManagePermissionsIsRejectedWith403() {
    when(iam.permissions(1L, 100L, null, null)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
        1L, 100L, null, null, 1, List.of("resource.view", "inventory.material.view", "product.view")));
    MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", bytes("png"));

    ApiException error = assertThrows(ApiException.class, () -> controller.upload(file));

    assertEquals(403, error.getStatus());
    assertEquals("PERMISSION_DENIED", error.getCode());
    assertEquals("无商品/物料/包厢图片管理权限", error.getMessage());
    verifyNoInteractions(images);
  }

  @Test
  void emptyPermissionSnapshotIsRejectedWith403() {
    when(iam.permissions(1L, 100L, null, null)).thenReturn(new TenantIamDomainClient.PermissionSnapshot(
        1L, 100L, null, null, 1, null));
    MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", bytes("png"));

    ApiException error = assertThrows(ApiException.class, () -> controller.upload(file));

    assertEquals(403, error.getStatus());
    verifyNoInteractions(images);
  }

  @Test
  void emptyFileIsRejected() {
    MockMultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

    ApiException error = assertThrows(ApiException.class, () -> controller.upload(file));

    assertEquals(400, error.getStatus());
    assertEquals("MEDIA_FILE_REQUIRED", error.getCode());
    verifyNoInteractions(images);
  }

  @Test
  void mediaServiceUnavailableIsReportedAs503() {
    MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", bytes("png"));
    when(images.upload(eq(100L), eq(null), eq("image/png"), any(byte[].class))).thenThrow(
        new ApiException(503, "MEDIA_UPLOAD_FAILED", "图片上传失败，请稍后重试"));

    ApiException error = assertThrows(ApiException.class, () -> controller.upload(file));

    assertEquals(503, error.getStatus());
    assertEquals("MEDIA_UPLOAD_FAILED", error.getCode());
    assertEquals("图片上传失败，请稍后重试", error.getMessage());
  }

  private static byte[] bytes(String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }
}
