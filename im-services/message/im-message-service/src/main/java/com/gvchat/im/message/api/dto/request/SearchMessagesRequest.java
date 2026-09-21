package com.gvchat.im.message.api.dto.request;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SearchMessagesRequest(@NotBlank String keyword, @NotBlank String peerId, @NotNull ChatType chatType, MsgType msgType,
    String beforeMsgId, Integer page, Integer pageSize) { }
