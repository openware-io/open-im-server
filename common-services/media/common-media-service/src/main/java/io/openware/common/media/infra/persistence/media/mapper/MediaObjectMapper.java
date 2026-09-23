package io.openware.common.media.infra.persistence.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.common.media.infra.persistence.media.po.MediaObjectPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MediaObjectMapper extends BaseMapper<MediaObjectPo> { }
