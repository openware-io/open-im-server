package io.openware.common.media.media;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.common.media.config.MediaProperties;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Release 制品（APK/AAB）上传：预签名直传对象存储，返回稳定下载 URL + SHA-256 + 大小，支持大文件（1GB 级）。 */
@Service
@RequiredArgsConstructor
public class ReleaseArtifactService {
  private final MediaStoragePort storage;
  private final MediaProperties properties;

  /** 创建直传会话：返回对象键 + 预签名 PUT URL，浏览器直传 MinIO，不经服务端中转（避免 multipart/内存上限）。 */
  public Map<String, Object> createUpload(String platform, String fileName, String contentType) {
    String safePlatform = safePlatform(platform);
    String objectKey = "release/" + safePlatform + "/" + UUID.randomUUID() + "-" + safeFileName(fileName);
    String uploadUrl = storage.uploadUrl(properties.privateBucket(), objectKey, contentType,
        Map.of("Content-Type", contentType));
    return Map.of("objectKey", objectKey, "uploadUrl", uploadUrl, "method", "PUT");
  }

  /** 完成直传：校验对象存在（键属 release 前缀），计算 SHA-256/大小，返回稳定下载 URL。 */
  public Map<String, Object> completeUpload(String platform, String fileName, String contentType, String objectKey) {
    String safePlatform = safePlatform(platform);
    if (objectKey == null || !objectKey.startsWith("release/" + safePlatform + "/")) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "对象键不合法");
    }
    String bucket = properties.privateBucket();
    long sizeBytes = storage.objectSize(bucket, objectKey);
    String sha256 = storage.sha256(bucket, objectKey);
    String downloadUrl = storage.accessUrl(bucket, objectKey);
    return Map.of("downloadUrl", downloadUrl, "sha256", sha256, "sizeBytes", sizeBytes);
  }

  private String safePlatform(String platform) {
    String value = platform == null ? "" : platform.trim().toLowerCase();
    if (!value.matches("[a-z0-9-]+")) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "平台标识不合法");
    }
    return value;
  }

  private String safeFileName(String fileName) {
    String name = fileName == null ? "" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    if (name.isBlank()) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "缺少文件名");
    }
    return name;
  }
}
