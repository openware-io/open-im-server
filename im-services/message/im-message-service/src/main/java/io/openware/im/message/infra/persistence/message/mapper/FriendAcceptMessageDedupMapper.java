package io.openware.im.message.infra.persistence.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.message.infra.persistence.message.po.FriendAcceptMessageDedupPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FriendAcceptMessageDedupMapper extends BaseMapper<FriendAcceptMessageDedupPo> {
}
