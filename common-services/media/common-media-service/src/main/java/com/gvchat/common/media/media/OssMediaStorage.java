package com.gvchat.common.media.media;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.CopyObjectRequest;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.PartETag;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.common.media.config.MediaProperties;
import java.io.InputStream;
import java.net.URL;
import java.time.Instant;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "media", name = "provider", havingValue = "oss")
@RequiredArgsConstructor
public class OssMediaStorage implements MediaStoragePort {
  private final MediaProperties properties;

  @Override
  public String uploadUrl(String bucket, String objectKey, String contentType,
      java.util.Map<String, String> requiredHeaders) {
    return signedUrl(bucket, objectKey, HttpMethod.PUT, properties.uploadSessionTtlSeconds(), contentType, requiredHeaders);
  }

  @Override
  public String partUploadUrl(String bucket, String objectKey, String contentType) {
    return uploadUrl(bucket, objectKey, contentType, java.util.Map.of("Content-Type", contentType));
  }

  @Override
  public void putObject(String bucket, String objectKey, String contentType, java.io.InputStream content, long size,
      java.util.Map<String, String> objectMetadata) {
    try {
      com.aliyun.oss.model.ObjectMetadata metadata = new com.aliyun.oss.model.ObjectMetadata();
      metadata.setContentType(contentType);
      // 图片/音视频落 inline，附件才落 attachment（见 MediaDispositions）：落反了会让「点开/预览」变成下载。
      metadata.setContentDisposition(MediaDispositions.of(contentType));
      if (objectMetadata != null) {
        objectMetadata.forEach(metadata::setHeader);
      }
      client().putObject(bucket, objectKey, content, metadata);
    } catch (Exception exception) {
      throw unavailable("媒体对象上传失败", exception);
    }
  }

  @Override
  public String accessUrl(String bucket, String objectKey) {
    // 聊天媒体采用稳定长期 URL（与 MinIO 对齐）：桶匿名可下载 + 不可猜测 UUID 对象键，
    // 地址随消息长期有效，仅随删除/撤回清理，不使用会过期的签名 URL。
    return properties.publicBaseUrl().replaceAll("/$", "") + "/" + bucket + "/" + objectKey;
  }

  @Override
  public long objectSize(String bucket, String objectKey) {
    try { return client().getObjectMetadata(bucket, objectKey).getContentLength(); }
    catch (Exception exception) { throw unavailable("已上传的媒体对象不可用", exception); }
  }

  @Override
  public String sha256(String bucket, String objectKey) {
    try (InputStream content = open(bucket, objectKey)) {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      for (int read; (read = content.read(buffer)) >= 0;) digest.update(buffer, 0, read);
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (Exception exception) { throw unavailable("已上传的媒体对象不可用", exception); }
  }

  @Override
  public boolean matchesContentSignature(String bucket, String objectKey, String contentType) {
    try (InputStream content = open(bucket, objectKey)) {
      byte[] header = content.readNBytes(12);
      return MediaSignatures.matches(contentType, header);
    } catch (Exception exception) { return false; }
  }

  @Override
  public InputStream open(String bucket, String objectKey) {
    try { return client().getObject(bucket, objectKey).getObjectContent(); }
    catch (Exception exception) { throw unavailable("已上传的媒体对象不可用", exception); }
  }

  @Override
  public String internalObjectUrl(String bucket, String objectKey) {
    return properties.endpoint().replaceAll("/$", "") + "/" + bucket + "/" + objectKey;
  }

  @Override
  public void move(String bucket, String sourceKey, String targetKey) {
    try { client().copyObject(new CopyObjectRequest(bucket, sourceKey, bucket, targetKey)); deleteObject(bucket, sourceKey); }
    catch (Exception exception) { throw unavailable("媒体存储收尾处理失败", exception); }
  }

  @Override
  public void compose(String bucket, java.util.List<String> sourceKeys, String targetKey, String contentType) {
    throw new ApiException(HttpStatusCodes.BAD_REQUEST, "OSS 分片合并需要原生分片适配器");
  }

  @Override
  public void deleteObject(String bucket, String objectKey) {
    try { client().deleteObject(bucket, objectKey); }
    catch (Exception exception) { throw unavailable("媒体对象删除失败", exception); }
  }

  @Override
  public String initiateMultipartUpload(String bucket, String objectKey, String contentType) {
    try {
      InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(bucket, objectKey);
      request.setObjectMetadata(new com.aliyun.oss.model.ObjectMetadata());
      request.getObjectMetadata().setContentType(contentType);
      return client().initiateMultipartUpload(request).getUploadId();
    } catch (Exception exception) { throw unavailable("媒体分片上传初始化失败", exception); }
  }

  @Override
  public String multipartPartUploadUrl(String bucket, String objectKey, String uploadId, int partNumber,
      String contentType) {
    try {
      GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.PUT);
      request.setContentType(contentType); request.setExpiration(Date.from(Instant.now().plusSeconds(properties.uploadSessionTtlSeconds())));
      request.addQueryParameter("uploadId", uploadId); request.addQueryParameter("partNumber", String.valueOf(partNumber));
      return client().generatePresignedUrl(request).toString();
    } catch (Exception exception) { throw unavailable("媒体分片上传地址签名失败", exception); }
  }

  @Override
  public void completeMultipartUpload(String bucket, String objectKey, String uploadId,
      java.util.List<CompletedPart> parts) {
    try {
      client().completeMultipartUpload(new CompleteMultipartUploadRequest(bucket, objectKey, uploadId,
          parts.stream().map(part -> new PartETag(part.partNumber(), part.etag())).toList()));
    } catch (Exception exception) { throw unavailable("媒体分片上传完成确认失败", exception); }
  }

  @Override
  public void abortMultipartUpload(String bucket, String objectKey, String uploadId) {
    try { client().abortMultipartUpload(new AbortMultipartUploadRequest(bucket, objectKey, uploadId)); }
    catch (Exception exception) { throw unavailable("媒体分片上传中止失败", exception); }
  }

  private String signedUrl(String bucket, String objectKey, HttpMethod method, long seconds, String contentType,
      java.util.Map<String, String> requiredHeaders) {
    try {
      GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, method);
      if (contentType != null) request.setContentType(contentType);
      for (java.util.Map.Entry<String, String> header : requiredHeaders.entrySet()) {
        if ("content-type".equalsIgnoreCase(header.getKey())) continue; // 已通过 setContentType 纳入签名
        request.addHeader(header.getKey(), header.getValue());
        request.addAdditionalHeaderName(header.getKey());
      }
      request.setExpiration(Date.from(Instant.now().plusSeconds(seconds)));
      URL url = client().generatePresignedUrl(request);
      return url.toString();
    } catch (Exception exception) { throw unavailable("媒体存储签名失败", exception); }
  }

  private OSS client() { return new OSSClientBuilder().build(properties.endpoint(), properties.accessKey(), properties.secretKey()); }
  private ApiException unavailable(String message, Exception exception) { return new ApiException(HttpStatusCodes.SERVICE_UNAVAILABLE, message); }
}
