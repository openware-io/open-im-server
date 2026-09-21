package com.gvchat.platform.resource.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.resource.infra.persistence.po.RoomTypePo;
import org.apache.ibatis.annotations.Mapper;

/** 房型字典（res_room_type）。租户边界由 MyBatis 租户拦截器自动附加 tenant_id。 */
@Mapper
public interface RoomTypeMapper extends BaseMapper<RoomTypePo> {
}
