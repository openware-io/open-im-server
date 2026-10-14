package io.openware.group.idaas.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.group.idaas.infra.persistence.po.IdaasAccountPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IdaasAccountMapper extends BaseMapper<IdaasAccountPo> {
}
