package io.openware.im.admin.application.query;

import io.openware.im.admin.integration.AdminReadClient;
import io.openware.common.dto.PageResult;
import io.openware.im.user.api.admin.AdminDeviceTokenResponse;
import io.openware.im.user.api.admin.AdminUserStickerResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminCrossDomainQueryService {
  private final AdminReadClient adminReadClient;

  public List<AdminDeviceTokenResponse> listDeviceTokens(Long userId) {
    return adminReadClient.listDeviceTokens(userId);
  }

  public PageResult<AdminDeviceTokenResponse> listAllDeviceTokens(int page, int pageSize, Long userId) {
    return adminReadClient.listAllDeviceTokens(page, pageSize, userId);
  }

  public void disableDeviceToken(Long id) {
    adminReadClient.disableDeviceToken(id);
  }

  public PageResult<AdminUserStickerResponse> listStickers(int page, int pageSize, Long userId) {
    return adminReadClient.listStickers(page, pageSize, userId);
  }

  public void deleteSticker(Long id) {
    adminReadClient.deleteSticker(id);
  }
}
