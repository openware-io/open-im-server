package com.gvchat.im.user.infra.persistence.sticker.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.user.infra.persistence.sticker.po.UserStickerPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserStickerMapper extends BaseMapper<UserStickerPo> {
}
