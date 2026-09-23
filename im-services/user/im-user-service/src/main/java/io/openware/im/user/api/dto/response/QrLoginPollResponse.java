package io.openware.im.user.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record QrLoginPollResponse(
    String status,
    @JsonProperty("access_token") String accessToken,
    AuthUserResponse user) {
}
