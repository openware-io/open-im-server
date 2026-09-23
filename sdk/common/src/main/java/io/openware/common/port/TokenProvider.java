package io.openware.common.port;

import java.time.Duration;

/**
 * Access token issuing contract implemented by infrastructure components such as JWT.
 */
public interface TokenProvider {
  String createAccessToken(long userId, String username, long authenticationVersion);

  default String createAccessToken(long userId, String username, long authenticationVersion, Duration ttl) {
    return createAccessToken(userId, username, authenticationVersion);
  }
}
