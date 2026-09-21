package com.gvchat.common.media.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.media.config.MediaProperties;
import com.gvchat.common.media.infra.persistence.media.mapper.MediaObjectMapper;
import com.gvchat.common.media.infra.persistence.media.mapper.MediaUploadSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class MediaUploadSessionServiceTest {

  @Test
  void shouldExplainSupportedFormatsWhenContentTypeIsNotAllowed() {
    MediaUploadSessionService service = new MediaUploadSessionService(properties(), mock(MediaStoragePort.class),
        mock(MediaObjectMapper.class), mock(MediaUploadSessionMapper.class), mock(ApplicationEventPublisher.class));

    assertThatThrownBy(() -> service.create(7L, "key-1", new MediaUploadSessionService.CreateUploadSession(
        "chat", "image", "animation.gif", "image/gif", 100L, "a".repeat(64), null)))
        .isInstanceOf(ApiException.class)
        .satisfies(exception -> assertThat(exception.getMessage()).isEqualTo("不支持的图片格式（image/gif），请上传：JPG、PNG、WebP"));
  }

  private static MediaProperties properties() {
    return new MediaProperties("minio", "http://localhost:9000", "http://localhost:9000", "us-east-1", "key",
        "secret", "private", 900, 900, 1, 10 * 1024 * 1024, 20 * 1024 * 1024, 50 * 1024 * 1024,
        200 * 1024 * 1024, 600000, 3600000, 40000000, "ffprobe", 10,
        "image/jpeg,image/png,image/webp", "audio/mpeg,audio/ogg,audio/wav,audio/mp4,audio/aac", "video/mp4",
        "application/pdf,text/plain,application/zip", "image/jpeg,image/png,application/pdf",
        "gv-media-public", "/api/v1/media-public");
  }
}
