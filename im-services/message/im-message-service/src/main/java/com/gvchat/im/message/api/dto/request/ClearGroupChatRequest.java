package com.gvchat.im.message.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ClearGroupChatRequest(@NotBlank String groupId) { }
