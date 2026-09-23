package io.openware.group.idaas.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.group.idaas.infra.persistence.po.GroupOrgPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface GroupOrgMapper extends BaseMapper<GroupOrgPo> {
}
