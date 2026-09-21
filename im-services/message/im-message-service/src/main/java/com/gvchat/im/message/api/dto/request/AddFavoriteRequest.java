package com.gvchat.im.message.api.dto.request;

import com.gvchat.common.enums.ChatType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AddFavoriteRequest(@NotBlank String msgId, @NotBlank String peerId, @NotNull ChatType chatType) { }
