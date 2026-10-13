package io.openware.im.admin.infra.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.openware.im.admin.infra.persistence.po.ProjectionEventPo;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectionEventMapper extends BaseMapper<ProjectionEventPo> {
  // 主键改为自增 `id` 后仍按业务键 `event_id` 做幂等；时间列已按审计列口径更名为 `created_at`（原 `processed_at`）。
  @Insert("INSERT IGNORE INTO adm_projection_event (event_id, event_type, created_at) VALUES (#{eventId}, #{eventType}, #{processedAt})")
  int insertIfAbsent(ProjectionEventPo value);
}
