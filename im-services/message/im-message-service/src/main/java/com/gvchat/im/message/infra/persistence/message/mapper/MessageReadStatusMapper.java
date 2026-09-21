package com.gvchat.im.message.infra.persistence.message.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.message.infra.persistence.message.po.MessageReadStatusPo;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MessageReadStatusMapper extends BaseMapper<MessageReadStatusPo> {
  @Select({"<script>",
      "SELECT msg_id, read_at FROM msg_read_status WHERE user_id = #{userId} AND msg_id IN",
      "<foreach collection='msgIds' item='msgId' open='(' separator=',' close=')'>#{msgId}</foreach>",
      "</script>"})
  List<MessageReadStatusPo> findByUserIdAndMsgIds(@Param("userId") long userId,
      @Param("msgIds") Collection<String> msgIds);
}
