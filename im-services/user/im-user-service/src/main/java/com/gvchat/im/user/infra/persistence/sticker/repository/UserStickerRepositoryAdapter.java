package com.gvchat.im.user.infra.persistence.sticker.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.common.dto.PageResult;
import com.gvchat.im.user.domain.sticker.model.UserSticker;
import com.gvchat.im.user.domain.sticker.repository.UserStickerRepository;
import com.gvchat.im.user.infra.persistence.sticker.converter.UserStickerPersistenceConverter;
import com.gvchat.im.user.infra.persistence.sticker.mapper.UserStickerMapper;
import com.gvchat.im.user.infra.persistence.sticker.mapper.UserStickerQuotaMapper;
import com.gvchat.im.user.infra.persistence.sticker.po.UserStickerPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserStickerRepositoryAdapter implements UserStickerRepository {
  private final UserStickerMapper mapper;
  private final UserStickerQuotaMapper quotaMapper;

  @Override
  public List<UserSticker> findByUserId(Long userId) {
    return mapper.selectList(Wrappers.<UserStickerPo>lambdaQuery()
            .eq(UserStickerPo::getUserId, userId)
            .orderByAsc(UserStickerPo::getSortOrder, UserStickerPo::getCreatedAt))
        .stream().map(UserStickerPersistenceConverter::toDomain).toList();
  }

  @Override
  public Optional<UserSticker> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id)).map(UserStickerPersistenceConverter::toDomain);
  }

  @Override
  public PageResult<UserSticker> findForAdmin(Long userId, int page, int pageSize) {
    Page<UserStickerPo> result = mapper.selectPage(new Page<>(page, pageSize),
        Wrappers.<UserStickerPo>lambdaQuery().eq(userId != null, UserStickerPo::getUserId, userId)
            .orderByDesc(UserStickerPo::getCreatedAt));
    return toPage(result, UserStickerPersistenceConverter::toDomain);
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
  public List<UserSticker> saveAll(List<UserSticker> stickers) {
    for (UserSticker sticker : stickers) {
      UserStickerPo po = UserStickerPersistenceConverter.toPo(sticker);
      mapper.insert(po);
      sticker.assignId(po.getId());
    }
    return stickers;
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
      java.util.function.Function<UserStickerPo, T> converter) {
    return PageResult.<T>builder().items(source.getRecords().stream().map(converter).toList()).total(source.getTotal())
        .page((int) source.getCurrent()).pageSize((int) source.getSize()).build();
  }
}
