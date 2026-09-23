package io.openware.im.message.api.dto.request;

import io.openware.common.enums.ChatType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MessageHistoryRequest(@NotBlank String peerId, @NotNull ChatType chatType, String beforeMsgId,
    String afterMsgId, String centerMsgId, Integer beforeCount, Integer afterCount, Integer page, Integer pageSize,
    String date) { }
