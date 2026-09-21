package com.gvchat.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.order.infra.persistence.po.CatalogItemPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CatalogItemMapper extends BaseMapper<CatalogItemPo> {
}
