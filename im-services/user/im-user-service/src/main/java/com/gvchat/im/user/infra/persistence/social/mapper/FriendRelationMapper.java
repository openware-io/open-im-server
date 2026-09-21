package com.gvchat.im.user.infra.persistence.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.user.infra.persistence.social.po.FriendRelationPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FriendRelationMapper extends BaseMapper<FriendRelationPo> {
}
