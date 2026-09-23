package io.openware.platform.resource.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.common.exception.BusinessException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 包厢图片/描述规则：≤9 张、URL ≤512、主图必须属于列表（未指定取第一张）、描述 ≤255（与商品同口径）。 */
class ResourceMediaTest {

  @Test
  void keepsMultipleImagesAndExplicitMainImage() {
    ResourceMedia.Images images = ResourceMedia.normalizeImages(
        List.of("/api/v1/media-public/a.png", "/api/v1/media-public/b.png", "/api/v1/media-public/c.png"), "/api/v1/media-public/b.png");

    assertEquals(3, images.urls().size());
    assertEquals("/api/v1/media-public/b.png", images.mainImageUrl());
  }

  @Test
  void defaultsMainImageToFirstWhenNotSpecified() {
    ResourceMedia.Images images = ResourceMedia.normalizeImages(List.of("/a.png", "/b.png"), null);

    assertEquals("/a.png", images.mainImageUrl());
  }

  @Test
  void defaultsMainImageToFirstWhenBlank() {
    ResourceMedia.Images images = ResourceMedia.normalizeImages(List.of("/a.png", "/b.png"), "   ");

    assertEquals("/a.png", images.mainImageUrl());
  }

  /**
   * 房型与包厢共用同一套图片规则，只有错误消息里的主体不同（房型图片也走 ResourceMedia，
   * 避免为房型复制一份会各自漂移的校验）。
   */
  @Test
  void allowsSubjectSpecificMessagesForRoomTypeImages() {
    ResourceMedia.Images images = ResourceMedia.normalizeImages(List.of("/a.png", "/b.png"), null, "房型");
    assertEquals(2, images.urls().size());
    assertEquals("/a.png", images.mainImageUrl());

    BusinessException tooMany = assertThrows(BusinessException.class,
        () -> ResourceMedia.normalizeImages(List.of("/1.png", "/2.png", "/3.png", "/4.png", "/5.png",
            "/6.png", "/7.png", "/8.png", "/9.png", "/10.png"), null, "房型"));
    assertEquals("房型图片最多 9 张，当前 10 张", tooMany.getMessage());

    BusinessException notInList = assertThrows(BusinessException.class,
        () -> ResourceMedia.normalizeImages(List.of("/a.png"), "/b.png", "房型"));
    assertEquals("房型主图必须是已上传图片中的一张", notInList.getMessage());
  }

  @Test
  void rejectsMainImageOutsideTheList() {
    BusinessException error = assertThrows(BusinessException.class,
        () -> ResourceMedia.normalizeImages(List.of("/a.png", "/b.png"), "/c.png"));

    assertEquals(ResourceMedia.ERROR_CODE, error.getCode());
    assertEquals("包厢主图必须是已上传图片中的一张", error.getMessage());
  }

  @Test
  void rejectsMoreThanNineImages() {
    List<String> urls = new ArrayList<>();
    for (int i = 0; i < ResourceMedia.MAX_IMAGES + 1; i++) {
      urls.add("/img/" + i + ".png");
    }

    BusinessException error = assertThrows(BusinessException.class,
        () -> ResourceMedia.normalizeImages(urls, null));

    assertEquals(ResourceMedia.ERROR_CODE, error.getCode());
    assertEquals("包厢图片最多 9 张，当前 10 张", error.getMessage());
  }

  @Test
  void acceptsExactlyNineImages() {
    List<String> urls = new ArrayList<>();
    for (int i = 0; i < ResourceMedia.MAX_IMAGES; i++) {
      urls.add("/img/" + i + ".png");
    }

    ResourceMedia.Images images = ResourceMedia.normalizeImages(urls, null);

    assertEquals(ResourceMedia.MAX_IMAGES, images.urls().size());
    assertEquals("/img/0.png", images.mainImageUrl());
  }

  @Test
  void rejectsOverlongImageUrl() {
    String longUrl = "/api/v1/media-public/" + "x".repeat(ResourceMedia.MAX_URL_LENGTH);

    BusinessException error = assertThrows(BusinessException.class,
        () -> ResourceMedia.normalizeImages(List.of(longUrl), null));

    assertEquals("包厢图片地址长度不能超过 512 个字符", error.getMessage());
  }

  @Test
  void rejectsOverlongMainImageUrl() {
    String longUrl = "/api/v1/media-public/" + "x".repeat(ResourceMedia.MAX_URL_LENGTH);

    BusinessException error = assertThrows(BusinessException.class,
        () -> ResourceMedia.normalizeImages(List.of("/a.png"), longUrl));

    assertEquals("包厢主图地址长度不能超过 512 个字符", error.getMessage());
  }

  @Test
  void blankAndDuplicateUrlsAreDroppedThenMainFollowsTheNormalizedList() {
    ResourceMedia.Images images = ResourceMedia.normalizeImages(
        Arrays.asList("  /a.png  ", "", "   ", "/a.png", null, "/b.png"), "/a.png");

    assertEquals(List.of("/a.png", "/b.png"), images.urls());
    assertEquals("/a.png", images.mainImageUrl());
  }

  @Test
  void emptyListClearsImagesAndMainImage() {
    ResourceMedia.Images images = ResourceMedia.normalizeImages(List.of(), "/stale.png");

    assertTrue(images.urls().isEmpty());
    assertNull(images.mainImageUrl());
  }

  @Test
  void nullListClearsImages() {
    ResourceMedia.Images images = ResourceMedia.normalizeImages(null, null);

    assertTrue(images.urls().isEmpty());
    assertNull(images.mainImageUrl());
  }

  @Test
  void keepsDescriptionWithinLimit() {
    assertEquals("可容纳 10 人的派对包厢", ResourceMedia.normalizeDescription("  可容纳 10 人的派对包厢  "));
  }

  @Test
  void blankDescriptionBecomesNullSoOperatorsCanClearIt() {
    assertNull(ResourceMedia.normalizeDescription("   "));
    assertNull(ResourceMedia.normalizeDescription(null));
  }

  @Test
  void rejectsOverlongDescription() {
    BusinessException error = assertThrows(BusinessException.class,
        () -> ResourceMedia.normalizeDescription("包".repeat(ResourceMedia.MAX_DESCRIPTION_LENGTH + 1)));

    assertEquals(ResourceMedia.ERROR_CODE, error.getCode());
    assertEquals("包厢描述长度不能超过 255 个字符", error.getMessage());
  }

  @Test
  void acceptsDescriptionOfExactlyMaxLength() {
    String description = "包".repeat(ResourceMedia.MAX_DESCRIPTION_LENGTH);

    assertEquals(description, ResourceMedia.normalizeDescription(description));
  }
}
