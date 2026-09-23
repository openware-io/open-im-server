package io.openware.im.user.domain.sticker.repository;

import io.openware.common.dto.PageResult;
import io.openware.im.user.domain.sticker.model.UserSticker;
import java.util.List;
import java.util.Optional;

public interface UserStickerRepository {
  List<UserSticker> findByUserId(Long userId);

  Optional<UserSticker> findById(Long id);

  /** 管理端贴纸分页查询：可选按用户过滤，按创建时间倒序。 */
  PageResult<UserSticker> findForAdmin(Long userId, int page, int pageSize);

  boolean reserveSlots(Long userId, int slotCount, int maximum);

  void releaseSlots(Long userId, int slotCount);

  List<UserSticker> saveAll(List<UserSticker> stickers);

  boolean deleteByIdAndUserId(Long id, Long userId);

  /** 管理端按主键硬删贴纸。 */
  void deleteById(Long id);
}
