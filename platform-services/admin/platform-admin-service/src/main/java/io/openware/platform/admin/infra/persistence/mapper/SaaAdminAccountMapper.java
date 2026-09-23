package io.openware.platform.admin.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.admin.infra.persistence.po.SaaAdminAccountPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SaaAdminAccountMapper extends BaseMapper<SaaAdminAccountPo> {
}
