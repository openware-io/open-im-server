package io.openware.im.admin.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.admin.infra.persistence.po.ViolationPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ViolationMapper extends BaseMapper<ViolationPo> {
}
