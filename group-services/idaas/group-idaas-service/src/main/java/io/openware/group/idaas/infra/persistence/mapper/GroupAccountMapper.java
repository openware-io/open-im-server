package io.openware.group.idaas.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.group.idaas.infra.persistence.po.GroupAccountPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GroupAccountMapper extends BaseMapper<GroupAccountPo> {
}
