package io.openware.im.user.domain.device.repository;

import io.openware.common.dto.PageResult;
import io.openware.common.enums.PushProvider;
import io.openware.im.user.domain.device.model.DeviceToken;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeviceTokenRepository {
  Optional<DeviceToken> findByUserIdAndPushProviderAndToken(Long userId, PushProvider pushProvider, String token);
  List<DeviceToken> findEnabledByUserId(Long userId);
  List<DeviceToken> findEnabledByUserIds(Collection<Long> userIds);
  Optional<DeviceToken> findById(Long id);
  /** 管理端启用设备令牌分页查询：可选按用户过滤，按更新时间倒序。 */
  PageResult<DeviceToken> findEnabledForAdmin(Long userId, int page, int pageSize);
  DeviceToken save(DeviceToken deviceToken);
}
