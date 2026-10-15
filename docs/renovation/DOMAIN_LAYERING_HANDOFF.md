# 领域分层治理交接：resource 域续做清单

> 本文档记录一次中断点。写完时工作区干净、HEAD 为 `32bee9d`，**主线主代码编译通过，但测试未同步（36 处编译错误）**。
> 不写这份文档的后果是：下一个执行者会重复本文档「§3 顺序陷阱」里已经踩过的坑。

## §1 治理方法（已连续验证 4 次，可复制）

每个限界上下文按同一流程执行，每步都要编译或测试验证：

| 步 | 内容 |
| --- | --- |
| A | **域勘察**：列出该域 PO/Mapper、应用层与控制器用法、既有测试与不变量 |
| B | **领域建模**：`domain.<ctx>.model` 聚合 + 有约束的行为方法（禁止整体 setter） |
| C | **端口**：`domain.<ctx>.repository`，语义化方法名，**不暴露 Wrapper/PO/Mapper/IPage** |
| D | **适配器**：`infra.persistence.repository`，承担审计字段填充与模型映射 |
| E | **改调用方**：服务与控制器改依赖端口；PO 不进 API（需 DTO + `api.converter`） |
| F | **改测试**：mock 端口而非 mapper |
| G | **验证**：编译 + 该域测试 + 门禁计数下降 |

已完成并清零的域：`identity`、`idaas`、`admin`、`marketing`。

## §2 resource 域当前状态

### 已完成

| 对象 | 产出 |
| --- | --- |
| `Resource` | `domain.resource.model`（20 字段，含联表快照）+ `ResourceRepository` + `ResourceRepositoryAdapter` |
| `RoomType` | `domain.roomtype.model` + `RoomTypeReader`（只读）+ **`RoomTypeRepository`（写路径）** + 2 个适配器 |
| `ResourceStateApplicationService` | 3 mapper → 2 端口；清洁迁移下沉 `Resource.withCleaning` |
| `RoomTypeApplicationService` | 2 mapper → 2 端口；控制器返回 `RoomTypeView` |
| 控制器 | `ResourceTypeController` 已改 DTO；`InternalResourceController` 已换注入 |

### 端口已具备的方法

```text
ResourceRepository
  listEnabledByType(resourceType, storeId)   // 「启用中」= status ENABLED，非 ACTIVE
  findById(id)
  save(resource)                             // id 为空则新增
  clearRoomTypeBinding(id)                   // ★ 见 §3
  countByRoomTypeId(roomTypeId)
  countByResourceCode(storeId, type, code, excludedId)

RoomTypeRepository
  listInStore(tenantId, storeId, status) / findById / findByIds / findInStore
  insert / updateSubmitted ★ / deleteById
  countByCode(tenantId, storeId, code, excludedId) / countByName(...)
```

### 剩余工作（`po-containment` 6 处）

| 文件 | 处数 | 内容 |
| --- | --- | --- |
| `api/controller/InternalResourceController.java` | 2 | 仍注入并直接使用 `ResourceMapper`（`selectById` 等） |
| `api/controller/OccupationController.java` | 1 | 用 `OccupationPo` |
| `api/controller/ResourceController.java` | 1 | 用 `ResourcePo` |
| `application/OccupationApplicationService.java` | 2 | 用 `OccupationMapper`/`OccupationPo` |

### 未解决的测试编译错误（36 处 / 3 文件）

- `api/controller/ResourceControllerTest.java`（645 行）
- `api/controller/RoomTypeUnbindRegressionTest.java`（306 行）
- `application/RoomTypeApplicationServiceTest.java`（529 行）

## §3 ★ 顺序陷阱（本文档最重要的内容）

**不要先改这三个测试。** 它们现在无法迁移到端口，原因是 **`ResourceController` 仍然自己下发 SQL**。

具体链路：

1. `ResourceController.doUpdate` 手工构造 `LambdaUpdateWrapper<ResourcePo>` 来清空 `room_type_id`。
2. `RoomTypeUnbindRegressionTest` 的假 Mapper **逐字节解析生成的 SET 子句**（正则匹配 `xxx=#{ew.paramNameValuePairs.MPGENVALn}`）来模拟真库写入。
3. 只要控制器还在生成那段 SQL，测试就不可能改为 mock 端口。

**正确顺序**：

| 步 | 动作 | 结果 |
| --- | --- | --- |
| 1 | **先迁移 `ResourceController`**（见 §4） | 控制器不再生成 SQL |
| 2 | 再迁移 `RoomTypeUnbindRegressionTest` | SQL 级断言**移入适配器测试**；控制器测试改为断言 `clearRoomTypeBinding(id)` 被调用 |
| 3 | 再迁移 `RoomTypeApplicationServiceTest` | 其中 `capturedUpdate()` 那批断言同样**属于适配器测试**（它们验证「null 是否真的进 SET」），控制器/服务测试只断言 `updateSubmitted(roomType)` 收到的模型**单价为 null** |
| 4 | 最后 `ResourceControllerTest` | `RoomTypePo` → `RoomType` 构造 + mock 端口声明同步改 |

### 为什么不能靠"重命名"完成迁移

一次尝试把 `RoomTypePo`→`RoomType`、`RoomTypeMapper`→`RoomTypeRepository`、`selectById`→`findById` 批量替换，**测试错误数从 36 升到 200**：类型名改了，但 mock 声明与旧类型的 setter 调用没跟着改。

> **迁移若同时改变"被设置的东西"（mock 声明、字段赋值方式），机械重命名不成立。**
> 正确做法：**先完整读完一个文件，再一次性重写它**，而不是「改一点 → 编译 → 再改」。

## §4 下一步：迁移 `ResourceController`（368 行）

当前它把 4 件事混在一起，应拆到应用服务：

| 现状（控制器内） | 应改为 |
| --- | --- |
| `list()` 拼 `LambdaQueryWrapper` + `enrichRoomTypes()` | 应用服务 `list(...)`；房型快照经 `RoomTypeApplicationService.listByIds` 回填 |
| `create()` 校验 + 建 PO + `insert` + 审计 | 应用服务用例；领域模型经 `ResourceRepository.save` |
| `doUpdate()` 拼 `LambdaUpdateWrapper`（含清空语义） | 领域迁移方法 + **`clearRoomTypeBinding`** 表达解绑 |
| `requireResourceCodeAvailable()` 拼条件 | 已就绪：`ResourceRepository.countByResourceCode(...)` |

**约束（不可退让）**：

- **清空语义必须用 `clearRoomTypeBinding`**，不得退回 `save()`：MyBatis-Plus 的 `NOT_NULL` 更新策略会把 null 列从 UPDATE 剔除，解绑会静默失效，`DELETE 房型` 永远 409 `ROOM_TYPE_IN_USE`。这是**已修缺陷**（`RoomTypeUnbindRegressionTest` 的注释明写"旧实现下本用例必然失败"）。
- 同理，房型清空单价走 `RoomTypeRepository.updateSubmitted`（显式列集），不得用 `updateById`。
- 区域 / 主图 / 描述传空白归一成 null 后**也必须真正落库为 NULL**，不能只清响应。
- 控制器改造完成后，`IMAGE_URLS_TYPE_HANDLER` 与 2 个 `LambdaUpdateWrapper` 应从控制器消失（JSON 类型处理器归适配器）。

## §5 之后：`Occupation` 聚合（最后一块）

`OccupationMapper` 有 5 个手写 SQL，**并发正确性关键路径**，迁移时**原样保留语义**：

| 方法 | 必须保住 |
| --- | --- |
| `selectValidForUpdate` | `FOR UPDATE` 行锁；锁在事务内持有至提交 |
| `selectActiveResourceIds` | `@InterceptorIgnore(tenantLine)` **跨租户**语义 |
| `selectExpiredHolds` / `selectEndedOccupations` | 定时任务的跨租户扫描 |
| `releaseWithVersion` | 乐观锁 + **`end_at` 单向收缩**：`GREATEST(start_at, LEAST(COALESCE(end_at, ?), ?))`，防负长度区间、防反向拉长 |
| `cancelWithVersion` | 乐观状态机 |

**这些必须写进端口 Javadoc**：若退化为"先读后判再写"，重复核销/覆盖有效占用等竞态会重新出现。

## §6 剩余 4 个域（按规模）

| 域 | `po-containment` |
| --- | --- |
| `platform-services/tenant` | 17 |
| `common-services/payment` | 26 |
| `platform-services/customer` | 33 |
| `platform-services/order` | 67（含已建的 `InventoryStock` 领域层） |

## §7 复现验证命令

```powershell
# 门禁（阻断级，必须 exit 0）
powershell -File scripts/validate/validate-domain-model-purity.ps1
powershell -File scripts/validate/validate-port-contract.ps1

# 存量门禁（报告模式，用于看计数）
powershell -File scripts/validate/validate-po-containment.ps1 -ReportOnly
powershell -File scripts/validate/validate-service-api-boundaries.ps1 -ReportOnly

# 某模块测试
.\mvnw.cmd --offline -B -ntp -pl platform-services/resource/platform-resource-service -am clean test
```

规范细则见 `docs/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md`（"领域模型与只读投影的边界"、"端口命名与契约"、"聚合装配与持久化契约"、"转换层职责切分"、"跨上下文读取"、"领域规范门禁"六节）。
