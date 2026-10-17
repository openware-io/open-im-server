package io.openware.im.user.domain.sticker.readmodel;

import java.time.LocalDateTime;

/**
 * 管理端表情列表的只读投影（standards/10 L266-279）。
 *
 * <p>列表是只读聚合查询、结果不参与状态变更决策，因此用投影而非领域模型；
 * 投影不定义业务行为、不被写路径当实体使用。
 *
 * @param id        表情行标识
 * @param userId    所属用户
 * @param url       资源地址
 * @param thumbnail 缩略图地址
 * @param sortOrder 排序位
 * @param createdAt 创建时刻（排序键）
 */
public record UserStickerView(Long id, Long userId, String url, String thumbnail, Integer sortOrder,
    LocalDateTime createdAt) {
}