package com.gvchat.im.message.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record MarkMessagesReadRequest(@NotEmpty List<String> msgIds) { }
