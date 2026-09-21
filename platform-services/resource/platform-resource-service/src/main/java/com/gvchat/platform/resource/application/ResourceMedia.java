package com.gvchat.platform.resource.application;

import com.gvchat.common.exception.BusinessException;
import java.util.List;

/**
 * 包厢（资源）图片与描述规则，与商品/物料保持一致（见 order 服务 ItemImages / ItemDescriptions）：
 * 图片最多 9 张、URL 长度 ≤ 512、主图必须来自图片列表（列表非空且未指定主图时自动取第一张）；
 * 描述 ≤ 255，空白串归一为 null。
 *
 * <p>规则违反抛 {@link BusinessException}，由本服务 GlobalExceptionHandler 统一映射为 400 + 可读中文消息。
 */
public final class ResourceMedia {

  public static final int MAX_IMAGES = 9;
  public static final int MAX_URL_LENGTH = 512;
  public static final int MAX_DESCRIPTION_LENGTH = 255;
  /** 校验失败的业务错误码：与商品 PRODUCT_INVALID 同风格。 */
  public static final String ERROR_CODE = "RESOURCE_INVALID";

  private ResourceMedia() {
  }

  /**
   * 规范化图片列表与主图：去空、去重、去首尾空白。
   * 列表为空时返回空列表 + null 主图（即「清空图片」）；列表非空时保证恰好一张主图。
   */
  public static Images normalizeImages(List<String> imageUrls, String mainImageUrl) {
    return normalizeImages(imageUrls, mainImageUrl, "包厢");
  }

  /**
   * 同 {@link #normalizeImages(List, String)}，但错误消息里的主体可指定（如「房型」）：
   * 房型与包厢共用同一套图片规则（最多 9 张、URL ≤ 512、主图必须来自列表），
   * 只有提示文案里的对象名不同，避免为房型复制一份会各自漂移的校验。
   */
  public static Images normalizeImages(List<String> imageUrls, String mainImageUrl, String subject) {
    List<String> urls = imageUrls == null ? List.of()
        : imageUrls.stream()
            .filter(url -> url != null && !url.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    if (urls.size() > MAX_IMAGES) {
      throw new BusinessException(ERROR_CODE, "%s图片最多 %d 张，当前 %d 张".formatted(subject, MAX_IMAGES, urls.size()));
    }
    if (urls.stream().anyMatch(url -> url.length() > MAX_URL_LENGTH)) {
      throw new BusinessException(ERROR_CODE, "%s图片地址长度不能超过 %d 个字符".formatted(subject, MAX_URL_LENGTH));
    }
    String main = mainImageUrl == null || mainImageUrl.isBlank() ? null : mainImageUrl.trim();
    if (main != null && main.length() > MAX_URL_LENGTH) {
      throw new BusinessException(ERROR_CODE, "%s主图地址长度不能超过 %d 个字符".formatted(subject, MAX_URL_LENGTH));
    }
    if (urls.isEmpty()) {
      return new Images(List.of(), null);
    }
    if (main == null) {
      main = urls.get(0);
    } else if (!urls.contains(main)) {
      throw new BusinessException(ERROR_CODE, "%s主图必须是已上传图片中的一张".formatted(subject));
    }
    return new Images(urls, main);
  }

  /** 规范化描述：null 原样返回（调用方据此判断「未提交该字段」），空白串归一为 null。 */
  public static String normalizeDescription(String description) {
    if (description == null) {
      return null;
    }
    String trimmed = description.trim();
    if (trimmed.length() > MAX_DESCRIPTION_LENGTH) {
      throw new BusinessException(ERROR_CODE, "包厢描述长度不能超过 %d 个字符".formatted(MAX_DESCRIPTION_LENGTH));
    }
    return trimmed.isEmpty() ? null : trimmed;
  }

  /** 规范化后的图片列表与主图。 */
  public record Images(List<String> urls, String mainImageUrl) {}
}
