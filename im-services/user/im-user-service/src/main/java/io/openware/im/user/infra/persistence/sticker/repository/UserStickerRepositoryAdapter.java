package io.openware.im.user.infra.persistence.sticker.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.openware.common.dto.PageResult;
import io.openware.im.user.domain.sticker.model.UserSticker;
import io.openware.im.user.domain.sticker.readmodel.UserStickerView;
import io.openware.im.user.domain.sticker.repository.AuditSnapshot;
import io.openware.im.user.domain.sticker.repository.Persisted;
import io.openware.im.user.domain.sticker.repository.UserStickerRepository;
import io.openware.im.user.infra.persistence.sticker.converter.UserStickerPersistenceConverter;
import io.openware.im.user.infra.persistence.sticker.mapper.UserStickerMapper;
import io.openware.im.user.infra.persistence.sticker.mapper.UserStickerQuotaMapper;
import io.openware.im.user.infra.persistence.sticker.po.UserStickerPo;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.function.Function;

@Repository
@RequiredArgsConstructor
public class UserStickerRepositoryAdapter implements UserStickerRepository {
  private final UserStickerMapper mapper;
  private final UserStickerQuotaMapper quotaMapper;

  @Override
  public List<Persisted<UserSticker>> findByUserId(Long userId) {
    return mapper.selectList(Wrappers.<UserStickerPo>lambdaQuery()
            .eq(UserStickerPo::getUserId, userId)
            .orderByAsc(UserStickerPo::getSortOrder, UserStickerPo::getCreatedAt))
        .stream().map(this::toPersisted).toList();
  }

  @Override
  public Optional<Persisted<UserSticker>> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(this::toPersisted);
  }

  @Override
  public PageResult<UserStickerView> findForAdmin(Long userId, int page, int pageSize) {
    Page<UserStickerPo> result = mapper.selectPage(new Page<>(page, pageSize),
        Wrappers.<UserStickerPo>lambdaQuery().eq(userId != null, UserStickerPo::getUserId, userId)
            .orderByDesc(UserStickerPo::getCreatedAt));
    return toPage(result, this::toView);
  }

  @Override
  public boolean reserveSlots(Long userId, int slotCount, int maximum) {
    quotaMapper.createIfAbsent(userId);
    return quotaMapper.reserveSlots(userId, slotCount, maximum) == 1;
  }

  @Override
  public void releaseSlots(Long userId, int slotCount) {
    quotaMapper.releaseSlots(userId, slotCount);
  }

  @Override
  public List<Persisted<UserSticker>> saveAll(List<UserSticker> stickers) {
    java.time.LocalDateTime now = java.time.LocalDateTime.now();
    List<Persisted<UserSticker>> result = new ArrayList<>(stickers.size());
    for (UserSticker sticker : stickers) {
      UserStickerPo po = UserStickerPersistenceConverter.toPo(sticker);
      // 审计列由适配器确定（standards/10 L299）：操作人恒为系统（0L）。
      po.setCreatedBy(0L);
      po.setUpdatedBy(0L);
      po.setCreatedAt(now);
      po.setUpdatedAt(now);
      mapper.insert(po);
      sticker.assignId(po.getId());
      result.add(toPersisted(po));
    }
    return result;
  }

  @Override
  public boolean deleteByIdAndUserId(Long id, Long userId) {
    return mapper.delete(Wrappers.<UserStickerPo>lambdaQuery()
        .eq(UserStickerPo::getId, id)
        .eq(UserStickerPo::getUserId, userId)) == 1;
  }

  @Override
  public void deleteById(Long id) {
    mapper.deleteById(id);
  }

  private static <T> PageResult<T> toPage(Page<UserStickerPo> source,
      Function<UserStickerPo, T> converter) {
    return PageResult.<T>builder().items(source.getRecords().stream().map(converter).toList()).total(source.getTotal())
        .page((int) source.getCurrent()).pageSize((int) source.getSize()).build();
  }

  /** 业务聚合 + 记账快照：用户端列表要回显创建时刻，而它不进领域模型。 */
  private Persisted<UserSticker> toPersisted(UserStickerPo po) {
    if (po == null) {
      return null;
    }
    return new Persisted<>(UserStickerPersistenceConverter.toDomain(po),
        new AuditSnapshot(po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedBy(), po.getUpdatedAt()));
  }

  /** 管理端列表投影：只读聚合查询，不参与状态变更（standards/10 L266-279）。 */
  private UserStickerView toView(UserStickerPo po) {
    return new UserStickerView(po.getId(), po.getUserId(), po.getUrl(), po.getThumbnail(), po.getSortOrder(),
        po.getCreatedAt());
  }
}
