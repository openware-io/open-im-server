package io.openware.im.admin.domain.miniapp;

import java.time.LocalDateTime;

/**
 * 小程序服务分组（服务类型）。
 *
 * @param hidden true=该分组不在「服务」列表展示；组内小程序仍可被搜索到、可固定到快捷应用区。
 */
public record MiniappServiceType(Integer id, String name, Integer sortOrder, Boolean hidden, LocalDateTime createdAt,
                                 LocalDateTime updatedAt) {
}
