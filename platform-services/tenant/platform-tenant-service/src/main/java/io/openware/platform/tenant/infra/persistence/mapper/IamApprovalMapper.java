package io.openware.platform.tenant.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.tenant.infra.persistence.po.IamApprovalPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IamApprovalMapper extends BaseMapper<IamApprovalPo> {
}
