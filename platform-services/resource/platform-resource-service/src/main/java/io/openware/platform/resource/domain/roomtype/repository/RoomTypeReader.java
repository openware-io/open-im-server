package io.openware.platform.resource.domain.roomtype.repository;

import io.openware.platform.resource.domain.roomtype.model.RoomType;
import java.util.List;
import java.util.Optional;

/**
 * 房型只读端口。
 *
 * <p>本端口当前只服务于资源状态视图的房型快照读取。房型的**写路径**（维护用例）在房型聚合改造时
 * 由 {@code RoomTypeApplicationService} 的端口承担，这里刻意只给读取能力，不臆造未使用的写方法。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或分页类型。
 */
public interface RoomTypeReader {

  /** 按标识读取房型。 */
  Optional<RoomType> findById(Long roomTypeId);

  /** 按标识批量读取房型（一次查询，避免逐个查库）。 */
  List<RoomType> findByIds(List<Long> roomTypeIds);
}
