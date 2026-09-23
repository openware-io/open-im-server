package io.openware.im.user.api.dto.request;

import jakarta.validation.constraints.NotBlank;

public record HandleFriendRequest(@NotBlank String action) {
}
