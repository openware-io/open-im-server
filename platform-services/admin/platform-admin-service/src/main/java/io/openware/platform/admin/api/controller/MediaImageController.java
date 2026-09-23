package io.openware.platform.admin.api.controller;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.infrastructure.tenant.TenantContext;
import io.openware.platform.admin.application.MediaImageApplicationService;
import io.openware.platform.admin.infra.TenantIamDomainClient;
import io.openware.platform.admin.infra.security.AdminContextHolder;
import io.openware.platform.admin.infra.security.AdminTenantContextTokenSigner;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * SaaS 后台商品/物料/包厢图片上传（admin BFF）：multipart 接收 → 校验运营上下文与权限 → 转发媒体服务。
 * 对外路径 /api/v1/admin/media/images（网关 StripPrefix=2 后为 /admin/media/images），
 * 对外契约不变（multipart 字段 file、响应 {url, objectKey, contentType, size}、URL 形态 /api/v1/media-public/...）。
 */
@RestController
@RequestMapping("/admin/media")
public class MediaImageController {

  /** 允许上传图片的权限码：商品管理、库存物料管理或资源（包厢）管理。 */
  private static final String PRODUCT_MANAGE = "product.manage";
  private static final String MATERIAL_MANAGE = "inventory.material.manage";
  private static final String RESOURCE_MANAGE = "resource.manage";
  private static final String DENIED_MESSAGE = "无商品/物料/包厢图片管理权限";

  private final MediaImageApplicationService images;
  private final TenantIamDomainClient tenantIamClient;
  private final AdminTenantContextTokenSigner contextTokens;

  public MediaImageController(MediaImageApplicationService images, TenantIamDomainClient tenantIamClient,
                              AdminTenantContextTokenSigner contextTokens) {
    this.images = images;
    this.tenantIamClient = tenantIamClient;
    this.contextTokens = contextTokens;
  }

  /** 上传单张图片，返回同源 URL（/api/v1/media-public/...）与对象键。 */
  @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public UploadedImageView upload(@RequestPart("file") MultipartFile file) {
    TenantContext context = requireManageContext();
    if (file == null || file.isEmpty()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "MEDIA_FILE_REQUIRED", "请选择要上传的图片");
    }
    try {
      MediaImageApplicationService.UploadedImage uploaded = images.upload(context.tenantId(), context.storeId(),
          file.getContentType(), file.getBytes());
      return new UploadedImageView(uploaded.url(), uploaded.objectKey(), uploaded.contentType(), uploaded.size());
    } catch (IOException exception) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "MEDIA_UPLOAD_FAILED", "图片读取失败，请重新选择");
    }
  }

  /** 运营上下文 + 权限校验：缺失 401，无商品/物料/包厢管理权限 403（与 StaffController.requireContext 同风格）。 */
  private TenantContext requireManageContext() {
    var admin = AdminContextHolder.get();
    if (admin == null || admin.tenantContextToken() == null) {
      throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "SAAS_CONTEXT_REQUIRED", "请先选择运营上下文");
    }
    TenantContext context;
    try {
      context = contextTokens.verify(admin.tenantContextToken());
    } catch (Exception exception) {
      throw new ApiException(HttpStatusCodes.UNAUTHORIZED, "SAAS_CONTEXT_INVALID", "运营上下文已过期，请刷新页面");
    }
    if (!Objects.equals(admin.platformAccountId(), context.accountId())) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "PERMISSION_DENIED", DENIED_MESSAGE);
    }
    var snapshot = tenantIamClient.permissions(context.accountId(), context.tenantId(),
        context.organizationId(), context.storeId());
    if (snapshot.permissions() == null
        || (!snapshot.permissions().contains(PRODUCT_MANAGE)
            && !snapshot.permissions().contains(MATERIAL_MANAGE)
            && !snapshot.permissions().contains(RESOURCE_MANAGE))) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "PERMISSION_DENIED", DENIED_MESSAGE);
    }
    return context;
  }

  public record UploadedImageView(String url, String objectKey, String contentType, long size) {}
}
