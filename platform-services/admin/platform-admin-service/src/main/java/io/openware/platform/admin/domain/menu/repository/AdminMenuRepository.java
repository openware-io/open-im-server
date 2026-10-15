package io.openware.platform.admin.domain.menu.repository;

import io.openware.platform.admin.domain.menu.readmodel.AdminMenuRow;
import java.util.List;

/**
 * 后台菜单只读端口。
 *
 * <p>只读端口归**调用方**（standards/10“跨上下文读取”）：菜单配置的维护归其自身的上下文，本端口只提供
 * 本上下文组装菜单树所需的读取能力，不反向依赖对方的持久化实现。
 *
 * <p>排序口径收在端口里（作用域层级 → 展示序号 → 标识）：这是菜单呈现的既定顺序，不该由调用方在
 * 应用层拼接查询条件。
 */
public interface AdminMenuRepository {

  /** 列出处于可用状态的菜单，按作用域层级、展示序号、标识升序。 */
  List<AdminMenuRow> findActiveOrdered();
}
