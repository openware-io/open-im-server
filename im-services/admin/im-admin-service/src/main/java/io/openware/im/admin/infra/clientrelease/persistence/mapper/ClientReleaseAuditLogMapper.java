package io.openware.im.admin.infra.clientrelease.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.admin.infra.clientrelease.persistence.po.ClientReleaseAuditLogPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ClientReleaseAuditLogMapper extends BaseMapper<ClientReleaseAuditLogPo> {
}
