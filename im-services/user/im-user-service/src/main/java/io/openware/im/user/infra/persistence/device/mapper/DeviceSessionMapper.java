package io.openware.im.user.infra.persistence.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.user.infra.persistence.device.po.DeviceSessionPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DeviceSessionMapper extends BaseMapper<DeviceSessionPo> {
}
