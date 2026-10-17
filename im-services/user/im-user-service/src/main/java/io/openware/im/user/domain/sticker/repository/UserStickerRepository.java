package io.openware.im.user.domain.sticker.repository;

import io.openware.common.dto.PageResult;
import io.openware.im.user.domain.sticker.model.UserSticker;
import io.openware.im.user.domain.sticker.readmodel.UserStickerView;
import java.util.List;
import java.util.Optional;

public interface UserStickerRepository {
  List<Persisted<UserSticker>> findByUserId(Long userId);

  Optional<Persisted<UserSticker>> findById(Long id);

  /** 管理端贴纸分页查询：可选按用户过滤，按创建时间倒序。 */
  PageResult<UserStickerView> findForAdmin(Long userId, int page, int pageSize);

  boolean reserveSlots(Long userId, int slotCount, int maximum);

  void releaseSlots(Long userId, int slotCount);

  List<Persisted<UserSticker>> saveAll(List<UserSticker> stickers);

  boolean deleteByIdAndUserId(Long id, Long userId);

  /** 管理端按主键硬删贴纸。 */
  void deleteById(Long id);
}
