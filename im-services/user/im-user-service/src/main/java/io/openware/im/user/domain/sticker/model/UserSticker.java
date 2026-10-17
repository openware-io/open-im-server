package io.openware.im.user.domain.sticker.model;

/**
 * 用户自定义表情：记录资源地址、缩略图与排序位。
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息，由适配器在写库时确定。
 * 用户端列表经 {@code UserStickerRepository.Persisted} 带入；管理端列表是只读聚合查询，
 * 按 L266-279 走 {@code UserStickerView} 投影。操作人恒为常量 {@code 0L}（系统导入）。
 */
public class UserSticker {
  private Long id;
  private Long userId;
  private String url;
  private String thumbnail;
  private Integer sortOrder;

  public static UserSticker create(Long userId, String url, String thumbnail) {
    UserSticker sticker = new UserSticker();
    sticker.userId = userId;
    sticker.url = url;
    sticker.thumbnail = thumbnail;
    sticker.sortOrder = 0;
    return sticker;
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public void restore(Long id, Long userId, String url, String thumbnail, Integer sortOrder) {
    this.id = id;
    this.userId = userId;
    this.url = url;
    this.thumbnail = thumbnail;
    this.sortOrder = sortOrder;
  }

  public void assignId(Long id) { this.id = id; }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getUrl() { return url; }
  public String getThumbnail() { return thumbnail; }
  public Integer getSortOrder() { return sortOrder; }
}
