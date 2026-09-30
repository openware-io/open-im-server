package io.openware.platform.admin.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.platform.admin.infra.persistence.po.AdminMenuPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminMenuMapper extends BaseMapper<AdminMenuPo> {}
