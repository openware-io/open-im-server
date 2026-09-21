package com.gvchat.common.media.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.media.config.MediaProperties;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 业务无关公开图片上传能力：入参校验、对象键规则与返回结构。 */
class MediaImageUploadServiceTest {

  private final MediaStoragePort storage = mock(MediaStoragePort.class);
  private final MediaImageUploadService service = new MediaImageUploadService(properties(), storage);

  @Test
  void uploadsImageIntoNeutralPublicBucketWithBizAndScopeKey() {
    var uploaded = service.upload("saas", "t100-s7", "image/png", 3L, stream("png"));

    assertThat(uploaded.bucket()).isEqualTo("gv-media-public");
    assertThat(uploaded.contentType()).isEqualTo("image/png");
    assertThat(uploaded.size()).isEqualTo(3L);
    assertThat(uploaded.objectKey()).matches("saas/t100-s7/\\d{6}/[0-9a-f]{32}\\.png");
    assertThat(uploaded.url()).isEqualTo("/api/v1/media-public/gv-media-public/" + uploaded.objectKey());
    verify(storage).ensurePublicReadBucket("gv-media-public");
    // 缓存与内容类型写进对象元数据：网关只转发，响应头必须由对象存储回放，否则去掉入口反代后缓存语义丢失。
    verify(storage).putObject(eq("gv-media-public"), eq(uploaded.objectKey()), eq("image/png"),
        any(ByteArrayInputStream.class), eq(3L),
        eq(Map.of("Cache-Control", MediaImageUploadService.PUBLIC_IMAGE_CACHE_CONTROL)));
  }

  @Test
  void omitsScopeSegmentWhenScopeIsBlankAndDefaultsBizToPublic() {
    assertThat(service.objectKey(null, "  ", "image/jpeg")).matches("public/\\d{6}/[0-9a-f]{32}\\.jpg");
    assertThat(service.objectKey("public", null, "image/jpeg")).doesNotContain("//");
  }

  @Test
  void keepsScopePathSafeWhenCallerSendsTraversalCharacters() {
    String key = service.objectKey("saas", "../../etc/passwd", "image/webp");

    assertThat(key).doesNotContain("..");
    assertThat(key).matches("saas/[A-Za-z0-9._-]+/\\d{6}/[0-9a-f]{32}\\.webp");
  }

  @Test
  void normalizesJpgAliasToJpeg() {
    var uploaded = service.upload("public", null, "image/jpg; charset=binary", 3L, stream("jpg"));

    assertThat(uploaded.contentType()).isEqualTo("image/jpeg");
    assertThat(uploaded.objectKey()).endsWith(".jpg");
  }

  @Test
  void rejectsUnsupportedContentTypeWithFourHundred() {
    ApiException error = assertThrows(ApiException.class,
        () -> service.upload("saas", "t1", "application/pdf", 3L, stream("pdf")));

    assertThat(error.getStatus()).isEqualTo(400);
    assertThat(error.getCode()).isEqualTo("MEDIA_TYPE_NOT_ALLOWED");
    assertThat(error.getMessage()).isEqualTo("仅支持 JPG/PNG/WebP 图片");
    verifyNoInteractions(storage);
  }

  @Test
  void rejectsOversizedImageWithFourHundred() {
    ApiException error = assertThrows(ApiException.class,
        () -> service.upload("saas", "t1", "image/jpeg", 10485761L, stream("jpg")));

    assertThat(error.getStatus()).isEqualTo(400);
    assertThat(error.getCode()).isEqualTo("MEDIA_TOO_LARGE");
    assertThat(error.getMessage()).contains("10MB");
    verifyNoInteractions(storage);
  }

  @Test
  void rejectsEmptyFileWithFourHundred() {
    ApiException error = assertThrows(ApiException.class,
        () -> service.upload("saas", "t1", "image/png", 0L, stream("")));

    assertThat(error.getStatus()).isEqualTo(400);
    assertThat(error.getCode()).isEqualTo("MEDIA_FILE_REQUIRED");
    verify(storage, never()).putObject(any(), any(), any(), any(), anyLong(), any());
    verify(storage, never()).ensurePublicReadBucket(any());
  }

  @Test
  void usesConfiguredPublicUrlPrefixAndBucket() {
    MediaImageUploadService customService = new MediaImageUploadService(
        propertiesWith("images-public", "https://cdn.example.com/media/"), storage);

    assertThat(customService.publicUrl("images-public", "saas/t1/202609/x.png"))
        .isEqualTo("https://cdn.example.com/media/images-public/saas/t1/202609/x.png");
  }

  /** 出厂兜底：未配置 public-url-prefix 时必须走网关 /api 命名空间，而不是依赖入口 rewrite 的 /media-public。 */
  @Test
  void fallsBackToGatewayApiPrefixWhenConfiguredPrefixIsBlank() {
    MediaImageUploadService defaulted = new MediaImageUploadService(propertiesWith("gv-media-public", "  "), storage);

    assertThat(defaulted.publicUrl("gv-media-public", "saas/t1/202609/x.png"))
        .isEqualTo("/api/v1/media-public/gv-media-public/saas/t1/202609/x.png");
  }

  private static ByteArrayInputStream stream(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }

  private static MediaProperties properties() {
    return propertiesWith("gv-media-public", "/api/v1/media-public");
  }

  private static MediaProperties propertiesWith(String publicBucket, String publicUrlPrefix) {
    return new MediaProperties("minio", "http://localhost:9000", "http://localhost:9000", "us-east-1", "key",
        "secret", "im-private", 900, 900, 1, 10 * 1024 * 1024, 20 * 1024 * 1024, 50 * 1024 * 1024,
        200 * 1024 * 1024, 600000, 3600000, 40000000, "ffprobe", 10,
        "image/jpeg,image/png,image/webp", "audio/mpeg,audio/ogg,audio/wav,audio/mp4,audio/aac", "video/mp4",
        "application/pdf,text/plain,application/zip", "image/jpeg,image/png,application/pdf",
        publicBucket, publicUrlPrefix);
  }
}
