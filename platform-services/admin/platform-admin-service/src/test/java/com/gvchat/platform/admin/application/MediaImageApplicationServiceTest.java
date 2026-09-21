package com.gvchat.platform.admin.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.platform.admin.infra.MediaServiceImageClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** BFF 编排层：把图片字节交给媒体服务，并把内部响应映射为对外契约。 */
class MediaImageApplicationServiceTest {

  private final MediaServiceImageClient mediaService = mock(MediaServiceImageClient.class);
  private final MediaImageApplicationService service = new MediaImageApplicationService(mediaService);

  @Test
  void uploadsThroughMediaServiceWithTenantAndStoreScope() {
    byte[] content = bytes("png");
    when(mediaService.upload(eq("saas"), eq("t100-s7"), eq("image/png"), eq(content))).thenReturn(
        new MediaServiceImageClient.UploadedImage(
            "/api/v1/media-public/gv-media-public/saas/t100-s7/202609/abc.png", "saas/t100-s7/202609/abc.png",
            "image/png", 3L));

    MediaImageApplicationService.UploadedImage uploaded = service.upload(100L, 7L, "image/png", content);

    assertEquals("/api/v1/media-public/gv-media-public/saas/t100-s7/202609/abc.png", uploaded.url());
    assertEquals("saas/t100-s7/202609/abc.png", uploaded.objectKey());
    assertEquals("image/png", uploaded.contentType());
    assertEquals(3L, uploaded.size());
  }

  @Test
  void usesTenantOnlyScopeWhenStoreIsAbsent() {
    byte[] content = bytes("png");
    when(mediaService.upload(any(), any(), any(), any())).thenReturn(
        new MediaServiceImageClient.UploadedImage("/api/v1/media-public/b/k.png", "k.png", "image/png", 3L));

    service.upload(100L, null, "image/png", content);

    verify(mediaService).upload(eq("saas"), eq("t100"), eq("image/png"), eq(content));
    assertEquals("t100", MediaImageApplicationService.scope(100L, null));
    assertEquals("t100-s7", MediaImageApplicationService.scope(100L, 7L));
  }

  @Test
  void passesThroughMediaServiceFourHundredMessage() {
    byte[] content = bytes("pdf");
    when(mediaService.upload(any(), any(), any(), any())).thenThrow(
        new ApiException(400, "MEDIA_TYPE_NOT_ALLOWED", "仅支持 JPG/PNG/WebP 图片"));

    ApiException error = assertThrows(ApiException.class, () -> service.upload(100L, 7L, "application/pdf", content));

    assertEquals(400, error.getStatus());
    assertEquals("MEDIA_TYPE_NOT_ALLOWED", error.getCode());
    assertEquals("仅支持 JPG/PNG/WebP 图片", error.getMessage());
  }

  @Test
  void reportsFiveOhThreeWhenMediaServiceIsUnavailable() {
    byte[] content = bytes("png");
    when(mediaService.upload(any(), any(), any(), any())).thenThrow(
        new ApiException(503, "MEDIA_UPLOAD_FAILED", "图片上传失败，请稍后重试"));

    ApiException error = assertThrows(ApiException.class, () -> service.upload(100L, 7L, "image/png", content));

    assertEquals(503, error.getStatus());
    assertEquals("MEDIA_UPLOAD_FAILED", error.getCode());
    assertEquals("图片上传失败，请稍后重试", error.getMessage());
  }

  private static byte[] bytes(String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }
}
