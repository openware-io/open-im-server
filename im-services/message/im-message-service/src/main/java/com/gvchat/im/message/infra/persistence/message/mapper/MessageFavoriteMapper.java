package com.gvchat.im.message.infra.persistence.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.message.infra.persistence.message.po.MessageFavoritePo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageFavoriteMapper extends BaseMapper<MessageFavoritePo> {
}
