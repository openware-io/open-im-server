package io.openware.im.message.api.dto.request;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SearchMessagesRequest(@NotBlank String keyword, @NotBlank String peerId, @NotNull ChatType chatType, MsgType msgType,
    String beforeMsgId, Integer page, Integer pageSize) { }
