package io.openware.platform.order.application;

import io.openware.common.exception.BusinessException;
import java.util.List;

/**
 * 商品/物料/目录项图片规则：最多 9 张、URL 长度 ≤ 512、主图必须来自图片列表。
 * 列表非空且未指定主图时，自动取第一张为主图，保证「有图即恰好一张主图」。
 */
public final class ItemImages {

  public static final int MAX_IMAGES = 9;
  public static final int MAX_URL_LENGTH = 512;

  private ItemImages() {
  }

  public static Images normalize(List<String> imageUrls, String mainImageUrl, String errorCode) {
    List<String> urls = imageUrls == null ? List.of()
        : imageUrls.stream().filter(url -> url != null && !url.isBlank()).map(String::trim).distinct().toList();
    if (urls.size() > MAX_IMAGES) {
      throw new BusinessException(errorCode, "图片最多 %d 张，当前 %d 张".formatted(MAX_IMAGES, urls.size()));
    }
    if (urls.stream().anyMatch(url -> url.length() > MAX_URL_LENGTH)) {
      throw new BusinessException(errorCode, "图片地址长度不能超过 %d 个字符".formatted(MAX_URL_LENGTH));
    }
    String main = mainImageUrl == null || mainImageUrl.isBlank() ? null : mainImageUrl.trim();
    if (main != null && main.length() > MAX_URL_LENGTH) {
      throw new BusinessException(errorCode, "主图地址长度不能超过 %d 个字符".formatted(MAX_URL_LENGTH));
    }
    if (urls.isEmpty()) {
      return new Images(List.of(), null);
    }
    if (main == null) {
      main = urls.getFirst();
    } else if (!urls.contains(main)) {
      throw new BusinessException(errorCode, "主图必须是已上传图片中的一张");
    }
    return new Images(urls, main);
  }

  /** 规范化后的图片列表与主图。 */
  public record Images(List<String> urls, String mainImageUrl) {}
}
