package io.openware.im.admin.infra.clientrelease.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.admin.infra.clientrelease.persistence.po.ClientReleaseOperationPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ClientReleaseOperationMapper extends BaseMapper<ClientReleaseOperationPo> {
}
