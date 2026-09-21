package com.gvchat.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.platform.order.infra.persistence.po.InventoryMaterialPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InventoryMaterialMapper extends BaseMapper<InventoryMaterialPo> {}
