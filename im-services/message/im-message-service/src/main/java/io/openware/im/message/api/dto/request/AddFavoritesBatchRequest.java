package io.openware.im.message.api.dto.request;

import io.openware.common.enums.ChatType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 批量收藏请求：与单条收藏同字段（peerId/chatType），另带本批消息 id 列表。
 * 单批上限 500 条，避免一次请求放大成无界循环。
 */
public record AddFavoritesBatchRequest(@NotBlank String peerId, @NotNull ChatType chatType,
    @NotEmpty @Size(max = 500) List<String> messageIds) { }
