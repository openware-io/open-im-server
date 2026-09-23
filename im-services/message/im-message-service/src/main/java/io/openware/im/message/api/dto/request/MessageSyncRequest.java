package io.openware.im.message.api.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record MessageSyncRequest(@Min(0) long afterSyncSeq, @Min(1) @Max(500) Integer limit) {
}
