package io.openware.common.media.controller;

import io.openware.common.media.media.MediaImageUploadService;
import java.io.ByteArrayInputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 业务无关的图片上传内部接口：供平台内部服务（如 SaaS 后台 BFF）直接调用，不使用 IM 用户态
 * （无 {@code SecurityUser}、无上传会话），仅走平台内部服务 HMAC 鉴权
 * （{@code /internal/**} 由 InternalServiceAuthenticationFilter 校验签名与 expected-source）。
 *
 * <p>请求体为图片原始字节，{@code Content-Type} 即图片 MIME；{@code biz}/{@code scope} 为查询参数，
 * 因此与请求体和路径一并纳入内部签名，调用方无法在传输途中篡改对象键归属。
 */
@RestController
@RequestMapping("/internal/media/images")
@RequiredArgsConstructor
public class InternalMediaImageController {
  private final MediaImageUploadService images;

  /** 上传单张图片，返回公开 URL、对象键、桶与元数据。 */
  @PostMapping
  public ImageUploadResponse upload(
      @RequestParam(value = "biz", required = false) String biz,
      @RequestParam(value = "scope", required = false) String scope,
      @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
      @RequestBody(required = false) byte[] content) {
    byte[] body = content == null ? new byte[0] : content;
    MediaImageUploadService.UploadedImage uploaded = images.upload(biz, scope, contentType, body.length,
        new ByteArrayInputStream(body));
    return new ImageUploadResponse(uploaded.url(), uploaded.objectKey(), uploaded.bucket(), uploaded.contentType(),
        uploaded.size());
  }

  /** 内部上传响应：url 为公开可引用地址，objectKey/bucket 便于业务侧清理与排查。 */
  public record ImageUploadResponse(String url, String objectKey, String bucket, String contentType, long size) { }
}
