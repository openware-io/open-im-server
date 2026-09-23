package io.openware.common.media.media;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.exception.ApiException;
import io.openware.common.media.config.MediaProperties;
import io.openware.common.media.infra.persistence.media.mapper.MediaUploadPartMapper;
import io.openware.common.media.infra.persistence.media.mapper.MediaUploadSessionMapper;
import io.openware.common.media.infra.persistence.media.po.MediaUploadPartPo;
import io.openware.common.media.infra.persistence.media.po.MediaUploadSessionPo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MediaMultipartUploadServiceTest {
  @Test
  void shouldReturnConfirmedPartEtagsWhenResuming() {
    MediaUploadSessionMapper sessions = mock(MediaUploadSessionMapper.class);
    MediaUploadPartMapper parts = mock(MediaUploadPartMapper.class);
    MediaMultipartUploadService service = new MediaMultipartUploadService(properties(), mock(MediaUploadSessionService.class),
        sessions, parts, mock(MediaStoragePort.class));
    when(sessions.selectOne(any())).thenReturn(session());
    when(parts.selectList(any())).thenReturn(List.of(part(2, "etag-2"), part(1, "etag-1")));

    Map<String, Object> status = service.status(7L, "session-1");

    assertThat(status.get("uploadedParts")).isEqualTo(List.of(
        new MediaMultipartUploadService.UploadedPart(1, "etag-1"),
        new MediaMultipartUploadService.UploadedPart(2, "etag-2")));
  }

  @Test
  void shouldCompleteOnlyWhenClientEtagsMatchConfirmedParts() {
    MediaUploadSessionMapper sessions = mock(MediaUploadSessionMapper.class);
    MediaUploadPartMapper parts = mock(MediaUploadPartMapper.class);
    MediaStoragePort storage = mock(MediaStoragePort.class);
    MediaUploadSessionService uploadSessions = mock(MediaUploadSessionService.class);
    MediaMultipartUploadService service = new MediaMultipartUploadService(properties(), uploadSessions, sessions, parts, storage);
    MediaUploadSessionPo session = session();
    when(sessions.selectOne(any())).thenReturn(session);
    when(parts.selectList(any())).thenReturn(List.of(part(1, "etag-1"), part(2, "etag-2")));
    when(uploadSessions.complete(eq(7L), eq("session-1"), any())).thenReturn(Map.of("objectId", "object-1"));

    service.complete(7L, "session-1", new MediaMultipartUploadService.CompleteMultipartUpload(
        List.of(new MediaMultipartUploadService.UploadedPart(1, "etag-1"),
            new MediaMultipartUploadService.UploadedPart(2, "etag-2")), 10L, session.getChecksumSha256()));

    verify(storage).completeMultipartUpload(eq("private"), eq("temp/upload/session-1"), eq("upload-1"), any());

    assertThatThrownBy(() -> service.complete(7L, "session-1",
        new MediaMultipartUploadService.CompleteMultipartUpload(
            List.of(new MediaMultipartUploadService.UploadedPart(1, "etag-1"),
                new MediaMultipartUploadService.UploadedPart(2, "other")), 10L, session.getChecksumSha256())))
        .isInstanceOf(ApiException.class);
    verify(storage, never()).abortMultipartUpload(any(), any(), any());
  }

  private static MediaUploadSessionPo session() {
    MediaUploadSessionPo value = new MediaUploadSessionPo();
    value.setUploadSessionId("session-1"); value.setObjectId("object-1"); value.setOwnerId(7L); value.setBucketName("private");
    value.setTemporaryObjectKey("temp/upload/session-1"); value.setStorageUploadId("upload-1");
    value.setContentType("video/mp4"); value.setSizeBytes(10L); value.setChecksumSha256("a".repeat(64));
    value.setPartSizeBytes(5L); value.setPartCount(2); value.setStatus("UPLOADING");
    value.setExpiresAt(LocalDateTime.now().plusMinutes(10));
    return value;
  }

  private static MediaUploadPartPo part(int number, String etag) {
    MediaUploadPartPo value = new MediaUploadPartPo();
    value.setPartNumber(number); value.setEtag(etag); value.setSizeBytes(5L); value.setStatus("UPLOADED");
    return value;
  }

  private static MediaProperties properties() {
    return new MediaProperties("minio", "http://localhost:9000", "http://localhost:9000", "us-east-1", "key",
        "secret", "private", 900, 900, 1, 10, 10, 10, 10, 10, 10, 10, "ffprobe", 10,
        "image/jpeg", "audio/mpeg", "video/mp4", "application/pdf", "application/pdf",
        "open-media-public", "/api/v1/media-public");
  }
}
