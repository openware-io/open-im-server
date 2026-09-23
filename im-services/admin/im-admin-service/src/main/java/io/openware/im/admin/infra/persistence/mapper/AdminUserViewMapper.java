package io.openware.im.admin.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.admin.infra.persistence.po.AdminUserViewPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminUserViewMapper extends BaseMapper<AdminUserViewPo> {
}
