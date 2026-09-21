package com.gvchat.im.user.infra.persistence.social.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.user.infra.persistence.social.po.FriendRequestPo;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FriendRequestMapper extends BaseMapper<FriendRequestPo> {
  @Select("SELECT * FROM user_friend_request WHERE id = #{id} FOR UPDATE")
  FriendRequestPo selectByIdForUpdate(Long id);
}
