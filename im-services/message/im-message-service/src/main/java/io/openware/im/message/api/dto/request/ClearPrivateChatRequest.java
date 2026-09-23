package io.openware.im.message.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ClearPrivateChatRequest(@NotBlank String peerId) { }
