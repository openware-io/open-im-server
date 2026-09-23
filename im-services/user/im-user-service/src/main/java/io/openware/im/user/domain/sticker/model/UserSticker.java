package io.openware.im.user.domain.sticker.model;

import java.time.LocalDateTime;

public class UserSticker {
  private Long id;
  private Long userId;
  private String url;
  private String thumbnail;
  private Integer sortOrder;
  private Long createdBy;
  private LocalDateTime createdAt;
  private Long updatedBy;
  private LocalDateTime updatedAt;

  public static UserSticker create(Long userId, String url, String thumbnail, LocalDateTime occurredAt) {
    UserSticker sticker = new UserSticker();
    sticker.userId = userId;
    sticker.url = url;
    sticker.thumbnail = thumbnail;
    sticker.sortOrder = 0;
    sticker.createdBy = 0L;
    sticker.createdAt = occurredAt;
    sticker.updatedBy = 0L;
    sticker.updatedAt = occurredAt;
    return sticker;
  }

  public void restore(
      Long id, Long userId, String url, String thumbnail, Integer sortOrder, Long createdBy,
      LocalDateTime createdAt, Long updatedBy, LocalDateTime updatedAt) {
    this.id = id;
    this.userId = userId;
    this.url = url;
    this.thumbnail = thumbnail;
    this.sortOrder = sortOrder;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedBy = updatedBy;
    this.updatedAt = updatedAt;
  }

  public void assignId(Long id) { this.id = id; }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getUrl() { return url; }
  public String getThumbnail() { return thumbnail; }
  public Integer getSortOrder() { return sortOrder; }
  public Long getCreatedBy() { return createdBy; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public Long getUpdatedBy() { return updatedBy; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
}
