package com.gvchat.common.media.media;

public interface MediaStoragePort {
  String uploadUrl(String bucket, String objectKey, String contentType, java.util.Map<String, String> requiredHeaders);

  /** 服务端直传（用于 release 制品等非客户端直传场景），不附加对象响应元数据。 */
  default void putObject(String bucket, String objectKey, String contentType, java.io.InputStream content, long size) {
    putObject(bucket, objectKey, contentType, content, size, java.util.Map.of());
  }

  /**
   * 服务端直传并写入对象响应元数据（如 {@code Cache-Control}）。
   *
   * <p>元数据保存在对象上，匿名 GetObject 时由对象存储原样回放到响应头（MinIO/S3/OSS 均是），
   * 用于让公共图片在浏览器与 CDN 侧长期缓存。
   */
  void putObject(String bucket, String objectKey, String contentType, java.io.InputStream content, long size,
      java.util.Map<String, String> objectMetadata);

  /**
   * 确保公开只读桶可用：不存在则创建，并设置允许匿名 GetObject 的桶策略（实现需幂等并缓存结果）。
   *
   * <p>默认实现拒绝：只有支持桶策略的提供方（MinIO/COS 等 S3 兼容实现）才能提供公共图片能力。
   */
  default void ensurePublicReadBucket(String bucket) {
    throw new com.gvchat.common.exception.ApiException(com.gvchat.common.http.HttpStatusCodes.SERVICE_UNAVAILABLE,
        "当前配置的媒体存储提供方不支持创建公开只读桶");
  }

  String accessUrl(String bucket, String objectKey);

  long objectSize(String bucket, String objectKey);

  String sha256(String bucket, String objectKey);

  boolean matchesContentSignature(String bucket, String objectKey, String contentType);

  java.io.InputStream open(String bucket, String objectKey);

  String internalObjectUrl(String bucket, String objectKey);

  void move(String bucket, String sourceKey, String targetKey);

  void deleteObject(String bucket, String objectKey);

  String partUploadUrl(String bucket, String objectKey, String contentType);

  void compose(String bucket, java.util.List<String> sourceKeys, String targetKey, String contentType);

  String initiateMultipartUpload(String bucket, String objectKey, String contentType);

  String multipartPartUploadUrl(String bucket, String objectKey, String uploadId, int partNumber, String contentType);

  void completeMultipartUpload(String bucket, String objectKey, String uploadId, java.util.List<CompletedPart> parts);

  void abortMultipartUpload(String bucket, String objectKey, String uploadId);

  record CompletedPart(int partNumber, String etag) { }
}
