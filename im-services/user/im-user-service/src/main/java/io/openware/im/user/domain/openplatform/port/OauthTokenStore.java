package io.openware.im.user.domain.openplatform.port;

import io.openware.im.user.domain.openplatform.model.OauthAccessToken;
import io.openware.im.user.domain.openplatform.model.OauthRefreshToken;
import java.util.Optional;

/** OAuth 令牌存储端口：access_token 短效、refresh_token 长效，均存于 Redis。 */
public interface OauthTokenStore {
  void saveAccessToken(String token, OauthAccessToken accessToken);

  Optional<OauthAccessToken> findAccessToken(String token);

  void removeAccessToken(String token);

  void saveRefreshToken(String token, OauthRefreshToken refreshToken);

  Optional<OauthRefreshToken> findRefreshToken(String token);

  void removeRefreshToken(String token);

  default boolean wasRefreshTokenUsed(String token) {
    return false;
  }

  /**
   * 撤销指定 token 家族：同一次授权/轮换链派生的全部 access/refresh token。
   *
   * <p>供 refresh_token 重放兜底使用：单次重放只登出该家族的派生 token，不影响同应用其它用户/其它家族。
   */
  void revokeFamily(String familyId);

  /**
   * 返回已被轮换消费（重放候选）的 refresh_token 所属家族 id。
   *
   * <p>refresh 轮换作废时会把家族 id 写入已用标记；查不到家族信息（遗留旧标记）时返回 empty，
   * 重放路径将不再升级为整应用撤销。
   */
  default Optional<String> usedRefreshTokenFamily(String token) {
    return Optional.empty();
  }

  void revokeApplication(String appId);

  void revokeApplicationUser(String appId, long userId);
}
