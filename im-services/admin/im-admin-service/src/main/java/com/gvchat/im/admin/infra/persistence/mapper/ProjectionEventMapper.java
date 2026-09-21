package com.gvchat.im.admin.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gvchat.im.admin.infra.persistence.po.ProjectionEventPo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectionEventMapper extends BaseMapper<ProjectionEventPo> {
  @Insert("INSERT IGNORE INTO adm_projection_event (event_id, event_type, processed_at) VALUES (#{eventId}, #{eventType}, #{processedAt})")
  int insertIfAbsent(ProjectionEventPo value);
}
