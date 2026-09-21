package com.gvchat.common.media.infra.persistence.media.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.common.media.infra.persistence.media.po.MediaUploadSessionPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MediaUploadSessionMapper extends BaseMapper<MediaUploadSessionPo> { }
