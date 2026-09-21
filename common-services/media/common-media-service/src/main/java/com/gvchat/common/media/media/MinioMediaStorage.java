package com.gvchat.common.media.media;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.common.media.config.MediaProperties;
import io.minio.BucketExistsArgs;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.ComposeObjectArgs;
import io.minio.ComposeSource;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.MinioAsyncClient;
import io.minio.SetBucketPolicyArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Part;
import com.google.common.collect.HashMultimap;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.io.InputStream;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(prefix = "media", name = "provider", havingValue = "minio")
@RequiredArgsConstructor
public class MinioMediaStorage implements MediaStoragePort {
  /** 匿名只读桶策略：仅开放 s3:GetObject，写入仍需服务端凭据。 */
  private static final String PUBLIC_READ_POLICY_TEMPLATE = """
      {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},"Action":["s3:GetObject"],\
      "Resource":["arn:aws:s3:::%s/*"]}]}""";

  private final MediaProperties properties;
  /** 已确认存在且已开放匿名只读的桶，避免每次上传都访问 MinIO 桶管理接口。 */
  private final Set<String> publicReadBuckets = ConcurrentHashMap.newKeySet();

  @Override
  public String uploadUrl(String bucket, String objectKey, String contentType,
      java.util.Map<String, String> requiredHeaders) {
    try {
      return publicClient().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.PUT)
          .bucket(bucket).object(objectKey).expiry(Math.toIntExact(properties.uploadSessionTtlSeconds()), TimeUnit.SECONDS)
          .extraHeaders(requiredHeaders).build());
    } catch (Exception exception) {
      throw unavailable("媒体上传地址签名失败", exception);
    }
  }

  @Override
  public void putObject(String bucket, String objectKey, String contentType, java.io.InputStream content, long size,
      java.util.Map<String, String> objectMetadata) {
    try {
      java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
      // 图片/音视频落 inline，附件才落 attachment（见 MediaDispositions）：落反了会让「点开/预览」变成下载。
      headers.put("Content-Disposition", MediaDispositions.of(contentType));
      if (objectMetadata != null) {
        headers.putAll(objectMetadata);
      }
      internalClient().putObject(io.minio.PutObjectArgs.builder()
          .bucket(bucket).object(objectKey)
          .stream(content, size, -1)
          .contentType(contentType)
          .headers(headers)
          .build());
    } catch (Exception exception) {
      throw unavailable("媒体对象上传失败", exception);
    }
  }

  @Override
  public void ensurePublicReadBucket(String bucket) {
    if (publicReadBuckets.contains(bucket)) {
      return;
    }
    synchronized (publicReadBuckets) {
      if (publicReadBuckets.contains(bucket)) {
        return;
      }
      try {
        MinioClient client = internalClient();
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
          client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
        client.setBucketPolicy(SetBucketPolicyArgs.builder().bucket(bucket)
            .config(PUBLIC_READ_POLICY_TEMPLATE.formatted(bucket)).build());
      } catch (Exception exception) {
        throw unavailable("公开只读桶初始化失败", exception);
      }
      publicReadBuckets.add(bucket);
    }
  }

  @Override
  public String accessUrl(String bucket, String objectKey) {
    // 聊天媒体采用稳定长期 URL（参考 Telegram/微信）：桶匿名可下载，对象键含不可猜测 UUID，
    // 地址随消息长期有效，仅随删除/撤回清理，不使用会过期的签名 URL。
    return properties.publicBaseUrl().replaceAll("/$", "") + "/" + bucket + "/" + objectKey;
  }

  @Override
  public long objectSize(String bucket, String objectKey) {
    try {
      return internalClient().statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build()).size();
    } catch (Exception exception) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "已上传的媒体对象不可用");
    }
  }

  @Override
  public String sha256(String bucket, String objectKey) {
    try (InputStream content = internalClient().getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      for (int read; (read = content.read(buffer)) >= 0;) {
        digest.update(buffer, 0, read);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (Exception exception) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "已上传的媒体对象不可用");
    }
  }

  @Override
  public boolean matchesContentSignature(String bucket, String objectKey, String contentType) {
    try (InputStream content = internalClient().getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
      byte[] header = content.readNBytes(12);
      if ("image/jpeg".equals(contentType)) return header.length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff;
      if ("image/png".equals(contentType)) return header.length >= 8 && (header[0] & 0xff) == 0x89 && header[1] == 0x50 && header[2] == 0x4e && header[3] == 0x47;
      if ("image/webp".equals(contentType)) return header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F' && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
      if ("application/pdf".equals(contentType)) return header.length >= 5 && header[0] == '%' && header[1] == 'P' && header[2] == 'D' && header[3] == 'F' && header[4] == '-';
      if ("video/mp4".equals(contentType) || "audio/mp4".equals(contentType)) return header.length >= 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
      if ("audio/wav".equals(contentType)) return header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F' && header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E';
      if ("audio/ogg".equals(contentType)) return header.length >= 4 && header[0] == 'O' && header[1] == 'g' && header[2] == 'g' && header[3] == 'S';
      if ("audio/mpeg".equals(contentType)) return header.length >= 3 && header[0] == 'I' && header[1] == 'D' && header[2] == '3';
      if ("application/vnd.android.package-archive".equals(contentType) || "application/zip".equals(contentType)) {
        return header.length < 4 || (header[0] == 'P' && header[1] == 'K' && header[2] == 3 && header[3] == 4);
      }
      if ("application/octet-stream".equals(contentType)) return true;
      return "text/plain".equals(contentType);
    } catch (Exception exception) {
      return false;
    }
  }

  @Override
  public InputStream open(String bucket, String objectKey) {
    try {
      return internalClient().getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
    } catch (Exception exception) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "已上传的媒体对象不可用");
    }
  }

  @Override
  public String internalObjectUrl(String bucket, String objectKey) {
    return properties.endpoint().replaceAll("/$", "") + "/" + bucket + "/" + objectKey;
  }

  @Override
  public void move(String bucket, String sourceKey, String targetKey) {
    try {
      internalClient().copyObject(CopyObjectArgs.builder().bucket(bucket).object(targetKey)
          .source(CopySource.builder().bucket(bucket).object(sourceKey).build()).build());
      deleteObject(bucket, sourceKey);
    } catch (Exception exception) {
      throw unavailable("媒体存储收尾处理失败", exception);
    }
  }

  @Override
  public void deleteObject(String bucket, String objectKey) {
    try {
      internalClient().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
    } catch (ErrorResponseException exception) {
      if (!"NoSuchKey".equals(exception.errorResponse().code())) {
        throw unavailable("媒体对象删除失败", exception);
      }
    } catch (Exception exception) {
      throw unavailable("媒体对象删除失败", exception);
    }
  }

  @Override
  public String partUploadUrl(String bucket, String objectKey, String contentType) {
    return uploadUrl(bucket, objectKey, contentType, java.util.Map.of("Content-Type", contentType));
  }

  @Override
  public void compose(String bucket, java.util.List<String> sourceKeys, String targetKey, String contentType) {
    try {
      var sources = sourceKeys.stream().map(key -> ComposeSource.builder().bucket(bucket).object(key).build()).toList();
      internalClient().composeObject(ComposeObjectArgs.builder().bucket(bucket).object(targetKey)
          .sources(sources).build());
    } catch (Exception exception) {
      throw unavailable("媒体分片合并失败", exception);
    }
  }

  @Override
  public String initiateMultipartUpload(String bucket, String objectKey, String contentType) {
    try {
      var headers = HashMultimap.<String, String>create();
      headers.put("Content-Type", contentType);
      return asyncInternalClient().createMultipartUploadAsync(bucket, properties.region(), objectKey, headers,
          HashMultimap.create()).get().result().uploadId();
    } catch (Exception exception) {
      throw unavailable("媒体分片上传初始化失败", exception);
    }
  }

  @Override
  public String multipartPartUploadUrl(String bucket, String objectKey, String uploadId, int partNumber,
      String contentType) {
    try {
      return publicAsyncClient().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.PUT)
          .bucket(bucket).object(objectKey).expiry(Math.toIntExact(properties.uploadSessionTtlSeconds()), TimeUnit.SECONDS)
          .extraHeaders(java.util.Map.of("Content-Type", contentType))
          .extraQueryParams(java.util.Map.of("uploadId", uploadId, "partNumber", String.valueOf(partNumber))).build());
    } catch (Exception exception) {
      throw unavailable("媒体分片上传地址签名失败", exception);
    }
  }

  @Override
  public void completeMultipartUpload(String bucket, String objectKey, String uploadId,
      java.util.List<CompletedPart> parts) {
    try {
      Part[] completed = parts.stream().map(part -> new Part(part.partNumber(), part.etag())).toArray(Part[]::new);
      asyncInternalClient().completeMultipartUploadAsync(bucket, properties.region(), objectKey, uploadId, completed,
          HashMultimap.create(), HashMultimap.create()).get();
    } catch (Exception exception) {
      throw unavailable("媒体分片上传完成确认失败", exception);
    }
  }

  @Override
  public void abortMultipartUpload(String bucket, String objectKey, String uploadId) {
    try {
      asyncInternalClient().abortMultipartUploadAsync(bucket, properties.region(), objectKey, uploadId,
          HashMultimap.create(), HashMultimap.create()).get();
    } catch (Exception exception) {
      throw unavailable("媒体分片上传中止失败", exception);
    }
  }

  private MinioClient internalClient() {
    return client(properties.endpoint());
  }

  private MinioClient publicClient() {
    return client(properties.publicBaseUrl());
  }

  private MinioAsyncClient asyncInternalClient() { return asyncClient(properties.endpoint()); }
  private MinioAsyncClient publicAsyncClient() { return asyncClient(properties.publicBaseUrl()); }
  private MinioAsyncClient asyncClient(String endpoint) {
    return MinioAsyncClient.builder().endpoint(endpoint).credentials(properties.accessKey(), properties.secretKey())
        .region(properties.region()).build();
  }

  private MinioClient client(String endpoint) {
    return MinioClient.builder().endpoint(endpoint).credentials(properties.accessKey(), properties.secretKey())
        .region(properties.region()).build();
  }

  private ApiException unavailable(String message, Exception exception) {
    return new ApiException(HttpStatusCodes.SERVICE_UNAVAILABLE, message);
  }
}
