# 业务域分类规范

[中文](05_DOMAIN_CLASSIFICATION.md) | [English](05_DOMAIN_CLASSIFICATION.en.md)

> 状态：`current`（`develop/2.4.0-hotel`）  
> 层级：L0 治理  
> 适用范围：im-saas 工程内所有业务上下文、平台能力、边缘应用。

## 目标与适用范围

本规范定义：

- 什么是核心业务域、支撑业务域、通用域、边缘应用；
- 如何判定一个上下文属于哪一类；
- 域之间的依赖方向；
- 新增/调整域时的登记、评审和门禁。

适用：

- 新增服务、模块、限界上下文；
- 把某个业务从单体拆成独立服务；
- 把某个能力从业务域提升为平台能力；
- 评估“这个功能应该放哪个域”。

不适用：

- 单个聚合/实体的设计；
- 技术框架选型；
- 部署拓扑本身。

## 术语与前置假设

- **限界上下文（Bounded Context）**：一个模型边界，有自己的语言和规则。
- **部署服务（Deployable）**：一个可运行进程/容器，可承载一个或多个上下文。
- **核心域（Core）**：形成业务差异化、直接创造收入或关键体验的域。
- **支撑域（Supporting）**：业务必需、可复用，但不形成差异化的域。
- **通用域（Generic）**：商品化、可采购/复用、不含业务规则的域。
- **边缘应用（Edge）**：BFF、网关、接入层，不拥有领域模型。

## 原则与约束

### 1. 先分上下文，再分服务

- 一个服务可以承载多个上下文，但代码必须分模块；
- 不能用“服务名”代替领域分类；
- 领域版图登记的是**上下文**，不是容器。

### 2. 判定标准

| 类型 | 判定问题 | 典型特征 |
| --- | --- | --- |
| Core | 它是否形成产品差异化？是否直接创造收入/关键体验？ | 业务规则复杂、变化快、需要业务专家 |
| Supporting | 是否多个业务都需要，但规则不形成差异化？ | 可平台化、可配置、平台团队维护 |
| Generic | 是否能直接采购/复用？是否不含业务规则？ | 身份协议、消息、文件、基础设施 |
| Edge | 是否只做协议转换/聚合/接入？ | BFF、Gateway、WebSocket 接入 |

补充规则：

- 如果“每个业务都要改它”，它大概率不是支撑域；
- 如果“没有它业务不能跑，但它不决定业务差异”，它是支撑域；
- 如果“可以买现成的”，它是通用域；
- 如果“只是给前端/外部做适配”，它是边缘应用。

### 3. 依赖方向

```text
Core -> Supporting -> Generic
Core -> Generic
Edge -> Core / Supporting
Supporting -X-> Core
Generic -X-> Core / Supporting
Core -X-> 另一个 Core 的内部模型
```

- 核心域可以依赖支撑域/通用域；
- 支撑域不能反向依赖核心域；
- 核心域之间只能通过发布语言/事件/ACL 协作，不能共享表；
- 边缘应用不拥有领域状态。

### 4. 支撑域不得写业务规则

支撑域可以提供：

```text
通用不变量、配置、扩展点、契约
```

不能提供：

```text
酒店房价规则、KTV 计费规则、饭店翻台规则
```

业务规则放回核心域；支撑域通过策略参数/扩展点执行。

### 5. 核心域不得被平台抽象吞掉

- 酒店容量、KTV 时段、饭店桌台、健身课程是各自核心域；
- 不能为了“统一”把它们抽象成一个公共容量表；
- 相似性沉淀为技术组件，不沉淀为共享领域模型。

### 6. 新增/调整域

新增或改变分类必须：

1. 更新 [DOMAIN_MAP](../40-domain/DOMAIN_MAP.md)；
2. 说明类型、Owner、实现模块、依赖、消费者；
3. 标为核心域时新增 ADR；
4. 通过 G53 `domain-map`；
5. 更新相关服务 README 和架构图。

### 7. Owner

| 类型 | Owner |
| --- | --- |
| Core | 业务产品负责人 + 领域技术负责人 |
| Supporting | 平台团队 Owner |
| Generic | 基础设施/平台 Owner |
| Edge | 对应前端/接入团队 |

## 模板或示例

```text
Context: Hotel Booking
Type: core
Owner: Hotel product + hotel tech lead
Modules: platform-order-service/domain/hotel
Depends on: Order Kernel, Payment, Customer, Stock, Audit
Consumers: C-end, Admin BFF, OTA
Why core: 房态、房价、超卖、入住规则形成酒店差异化
```

## 门禁与验证

- **G53 domain-map**：
  - 所有服务目录在版图中出现；
  - 每个上下文有合法类型、Owner、实现模块；
  - 核心域必须有 ADR 引用；
  - 类型只能是 `core/supporting/generic/edge`。
- G18/G24/G25/G32/G43 继续约束代码边界、端口、PO、租户 SQL 和目录。

## 可验证的验收标准

- 新服务/模块有明确上下文和类型；
- 核心域数量可控，不出现“全是核心域”；
- 支撑域没有业务规则；
- 依赖方向符合 Core → Supporting → Generic；
- 核心域之间有 ACL/事件，无共享表；
- G53 通过。

## 与其他规范的关系

- [工程原则](03_ENGINEERING_PRINCIPLES.md)：边界与单一所有权；
- [门禁治理](04_GATE_GOVERNANCE.md)：G53 的登记和退役；
- [DDD 服务工程规范](10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)：模块/分层；
- [领域版图](../40-domain/DOMAIN_MAP.md)：实际分类结果；
- [库存平台域](../40-domain/inventory/README.md)：Stock 支撑域，Capacity 归核心域；
- [订单平台域](../40-domain/order/README.md)：Order Kernel 支撑域。
