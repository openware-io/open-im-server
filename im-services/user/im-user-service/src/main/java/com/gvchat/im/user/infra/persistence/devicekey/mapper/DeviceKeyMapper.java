package com.gvchat.im.user.infra.persistence.devicekey.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.user.infra.persistence.devicekey.po.DeviceKeyPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceKeyMapper extends BaseMapper<DeviceKeyPo> {
}
