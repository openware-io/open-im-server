package com.gvchat.im.message.infra.persistence.message.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.gvchat.im.message.domain.message.model.MessageFavorite;
import com.gvchat.im.message.domain.message.repository.MessageFavoriteRepository;
import com.gvchat.im.message.infra.persistence.message.MessagePersistenceConverter;
import com.gvchat.im.message.infra.persistence.message.mapper.MessageFavoriteMapper;
import com.gvchat.im.message.infra.persistence.message.po.MessageFavoritePo;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MessageFavoriteRepositoryAdapter implements MessageFavoriteRepository {
  private final MessageFavoriteMapper mapper;

  @Override
  public boolean saveIfAbsent(MessageFavorite favorite) {
    MessageFavoritePo po = MessagePersistenceConverter.toPo(favorite);
    try {
      mapper.insert(po);
      favorite.assignId(po.getId());
      return true;
    } catch (DuplicateKeyException ex) {
      // 命中 (user_id, msg_id) 唯一约束：重复收藏幂等，不重复插入。
      return false;
    }
  }

  @Override
  public void deleteByUserIdAndMsgId(long userId, String msgId) {
    mapper.delete(Wrappers.<MessageFavoritePo>lambdaQuery()
        .eq(MessageFavoritePo::getUserId, userId)
        .eq(MessageFavoritePo::getMsgId, msgId));
  }

  @Override
  public void deleteByMsgId(String msgId) {
    mapper.delete(Wrappers.<MessageFavoritePo>lambdaQuery().eq(MessageFavoritePo::getMsgId, msgId));
  }

  @Override
  public FavoritePage findByUserId(long userId, int page, int pageSize) {
    // 按收藏时间倒序；追加 id DESC 作为确定性 tie-break（created_at 为毫秒精度，可能重复）。
    Page<MessageFavoritePo> result = mapper.selectPage(new Page<>(page, pageSize),
        Wrappers.<MessageFavoritePo>lambdaQuery()
            .eq(MessageFavoritePo::getUserId, userId)
            .orderByDesc(MessageFavoritePo::getCreatedAt)
            .orderByDesc(MessageFavoritePo::getId));
    return new FavoritePage(result.getRecords().stream().map(MessagePersistenceConverter::toDomain).toList(),
        result.getTotal());
  }
}
