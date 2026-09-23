package io.openware.im.user.api.converter;

import io.openware.im.user.api.dto.response.AuthUserResponse;
import io.openware.im.user.api.dto.response.TokenResponse;
import io.openware.im.user.api.dto.response.UserProfileResponse;
import io.openware.im.user.application.account.result.AuthenticatedAccountResult;
import io.openware.im.user.application.profile.result.UserProfileResult;

public final class UserAccountApiConverter {
  private UserAccountApiConverter() {
  }

  public static TokenResponse toTokenResponse(AuthenticatedAccountResult result) {
    return new TokenResponse(result.accessToken(),
        new AuthUserResponse(result.id(), result.username(), result.nickname(), result.avatar()));
  }

  public static UserProfileResponse toProfileResponse(UserProfileResult result) {
    return new UserProfileResponse(
        result.id(), result.username(), result.nickname(), result.avatar(), result.email(), result.phone(),
        result.signature(), result.status(), result.role(), result.createdBy(), result.createdAt(),
        result.updatedBy(), result.updatedAt());
  }
}
