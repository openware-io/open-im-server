package com.gvchat.im.admin.infra.clientrelease.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.admin.infra.clientrelease.persistence.po.ClientReleaseOperationPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ClientReleaseOperationMapper extends BaseMapper<ClientReleaseOperationPo> {
}
