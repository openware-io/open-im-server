package com.gvchat.im.message.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ClearPrivateChatRequest(@NotBlank String peerId) { }
