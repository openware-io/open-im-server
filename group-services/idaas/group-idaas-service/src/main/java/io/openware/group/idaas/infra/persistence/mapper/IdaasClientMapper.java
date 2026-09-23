package io.openware.group.idaas.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.group.idaas.infra.persistence.po.IdaasClientPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdaasClientMapper extends BaseMapper<IdaasClientPo> {
}
