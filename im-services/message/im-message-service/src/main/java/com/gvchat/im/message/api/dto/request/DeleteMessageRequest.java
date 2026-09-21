package com.gvchat.im.message.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record DeleteMessageRequest(@NotBlank String msgId) { }
