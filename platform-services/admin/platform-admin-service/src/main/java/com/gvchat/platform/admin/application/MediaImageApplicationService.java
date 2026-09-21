package com.gvchat.platform.admin.application;

import com.gvchat.platform.admin.infra.MediaServiceImageClient;
import org.springframework.stereotype.Service;

/**
 * SaaS 后台图片上传（BFF 编排层）：把图片字节转发给媒体服务（公共图片能力），
 * 并把内部响应映射为后台既有对外契约 {@code {url, objectKey, contentType, size}}。
 *
 * <p>图片的存储、桶、对象键与 URL 形态全部由 common-media-service 决定；BFF 不再校验类型/大小、
 * 也不再持有对象存储凭据。业务行自行保存返回的 URL。
 */
@Service
public class MediaImageApplicationService {

  /** 业务标识：SaaS 后台商品/物料图片。 */
  private static final String BIZ = "saas";

  private final MediaServiceImageClient mediaService;

  public MediaImageApplicationService(MediaServiceImageClient mediaService) {
    this.mediaService = mediaService;
  }

  /** 上传图片并返回公开 URL 与对象键；scope 由运营上下文的租户/门店组成。 */
  public UploadedImage upload(long tenantId, Long storeId, String contentType, byte[] content) {
    MediaServiceImageClient.UploadedImage uploaded = mediaService.upload(BIZ, scope(tenantId, storeId), contentType,
        content);
    return new UploadedImage(uploaded.url(), uploaded.objectKey(), uploaded.contentType(), uploaded.size());
  }

  /** 对象键归属段：租户必选，门店可选（t{tenantId}[-s{storeId}]）。 */
  static String scope(long tenantId, Long storeId) {
    return storeId == null ? "t" + tenantId : "t" + tenantId + "-s" + storeId;
  }

  /** 上传结果：url 为同源可访问地址（/api/v1/media-public/{bucket}/{objectKey}），objectKey 便于清理/排查。 */
  public record UploadedImage(String url, String objectKey, String contentType, long size) { }
}
