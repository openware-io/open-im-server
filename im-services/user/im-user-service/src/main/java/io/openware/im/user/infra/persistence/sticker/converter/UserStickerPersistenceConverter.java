package io.openware.im.user.infra.persistence.sticker.converter;

import io.openware.im.user.domain.sticker.model.UserSticker;
import io.openware.im.user.infra.persistence.sticker.po.UserStickerPo;

public final class UserStickerPersistenceConverter {
  private UserStickerPersistenceConverter() {
  }

  public static UserSticker toDomain(UserStickerPo po) {
    UserSticker sticker = new UserSticker();
    sticker.restore(po.getId(), po.getUserId(), po.getUrl(), po.getThumbnail(), po.getSortOrder());
    return sticker;
  }

  public static UserStickerPo toPo(UserSticker sticker) {
    UserStickerPo po = new UserStickerPo();
    po.setId(sticker.getId());
    po.setUserId(sticker.getUserId());
    po.setUrl(sticker.getUrl());
    po.setThumbnail(sticker.getThumbnail());
    po.setSortOrder(sticker.getSortOrder());
    return po;
  }
}
