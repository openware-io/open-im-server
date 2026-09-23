package io.openware.im.user.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenResponse(@JsonProperty("access_token") String accessToken, AuthUserResponse user) {
}
