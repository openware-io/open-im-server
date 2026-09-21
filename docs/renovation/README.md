# 专项改造方案命名规范

## 适用范围

本规范适用于 `docs/renovation/` 中新建或重命名的专项改造方案。已有历史文件不因本规范批量重命名；当其发生实质性重写时，再按本规范迁移并更新引用。

## 文件名格式

```text
<SERIES>_<NN>_<AREA>.md
```

| 字段 | 规则 | 示例 |
| --- | --- | --- |
| `SERIES` | 2 至 4 个大写英文单词，以 `_` 分隔，表达一套可独立交付的改造主题。 | `MEDIA_UPLOAD` |
| `NN` | 两位十进制顺序号，从 `01` 开始；同一主题内不可重复。 | `01` |
| `AREA` | 一个大写英文单词，表达实施边界。优先使用 `SERVICE`、`APP`、`ADMIN`、`GATEWAY`、`DATA`、`TEST`。 | `ADMIN` |

同一改造主题必须共用相同的 `SERIES`，并按依赖顺序编号。文件名不包含 `PLAN`、`RENOVATION`、日期、版本号、团队名、环境名或项目私有前缀；这些信息写入文档标题、范围和变更记录。

## 方案集规则

- 每份方案在开头声明所属方案集、顺序号、前置/后置方案，并链接对应工程标准。
- 跨端改造必须拆为独立 `APP` 与 `ADMIN` 文件；共同的服务端能力写入 `SERVICE`，不能复制到客户端方案中。
- 网关、数据库或测试工作如复杂到可独立排期，使用同一 `SERIES` 的后续编号单列；否则写入拥有该工作的服务端方案。
- 标准、契约和实施方案职责分离：`docs/standards/` 定义长期规则，OpenAPI/AsyncAPI 定义机器可读契约，`docs/renovation/` 仅描述当前迁移步骤、模块影响、灰度与验收。
- 新方案必须在“变更清单”中列出需要同步的 OpenAPI、Flyway、服务端、App、PC 后台、测试和部署配置；实施步骤不得暗示未实现能力已经上线。

## 本次媒体方案集

```text
MEDIA_UPLOAD_01_SERVICE.md  服务端、Gateway、数据与存储适配器
MEDIA_UPLOAD_02_APP.md      Flutter App
MEDIA_UPLOAD_03_ADMIN.md    PC 管理后台
```

## 消息同步方案集

```text
MESSAGE_DOMAIN_01_DDD.md    消息域边界与数据职责
MESSAGE_SYNC_01_SERVICE.md  服务端、网关、数据与契约
MESSAGE_SYNC_02_APP.md      Flutter App 同步状态与交互
MESSAGE_SYNC_03_TEST.md     自动化、联调与验收
```

## 聊天可靠性与群治理方案集

```text
CHAT_RELIABILITY_01_SERVICE.md  消息接续、已读、撤回、群治理、推送和 ACK 发布
CHAT_RELIABILITY_02_APP.md      Flutter App 同步、群资料和极光通知集成
```

## 客户端发布治理方案集

```text
APP_RELEASE_01_SERVICE.md  服务端、Gateway、数据、契约与发布任务
APP_RELEASE_02_APP.md      Flutter App 的新更新协议、各系统制品与升级实现
APP_RELEASE_03_ADMIN.md    PC 管理后台的发布治理工作台与 API Client
```

## SaaS 平台方案集

```text
SAAS_PLATFORM_01_SERVICE.md  业务基线、领域边界、SaaS/多业态总体方案
SAAS_PLATFORM_02_SERVICE.md  微服务、数据、支付、履约与 PC Admin 实施方案
SAAS_PLATFORM_03_APP.md      B 端门店运营 App 实施方案
SAAS_PLATFORM_04_DATA.md     核心表、字段、索引、账本与初始化设计
SAAS_PLATFORM_05_API.md      REST、Admin、B App 与 MQ 事件契约
SAAS_PLATFORM_06_TECHNICAL.md 租户隔离、并发、资金一致性、风控与发布方案
SAAS_PLATFORM_07_EXECUTION.md 代码目录、依赖顺序、工作包与发布完成定义
SAAS_PLATFORM_08_APP.md       B 端 App 低保真交互原型、关键流程与异常体验规范
SAAS_PLATFORM_09_SERVICE.md   DDD 聚合、端口、领域事件、事务与测试映射
```

## KTV 业务细化方案集

```text
KTV_BUSINESS_01_SERVICE.md  KTV 业务闭环、计时计费、包厢/加项/收银/日结、权限与异常处理
KTV_BUSINESS_02_APP.md      B 端 KTV 页面/交互可派工规格
KTV_BUSINESS_03_ADMIN.md    PC 后台配置（计价方案/服务人员/支付开关；储值走租户级「储值管理」页）
KTV_BUSINESS_04_TEST.md     测试分层、UT 清单、E2E 与联调发布门槛
```

## 多端登录方案集

```text
MULTI_DEVICE_LOGIN_01_SERVICE.md  多端登录与设备管理（主/副设备、扫码授权、E2EE 多端）
```

## IM 开放平台方案集

```text
IM_OPEN_PLATFORM_01_SERVICE.md  第三方系统标准接入（OAuth：申请/审批/授权/同步/打通，SaaS/打车/电商等）
IM_OPEN_PLATFORM_02_INTEGRATION_GUIDE.md  第三方接入指南 v3（申请→审批→前台授权→后台换证；前后台差异；A380 实例；自测与验收）
IM_OPEN_PLATFORM_03_QUICKSTART.md  第三方接入快速上手（先申请审批，再 PKCE S256 + curl 全流程）
IM_OPEN_PLATFORM_04_SERVICE.md  服务板块接入（小程序服务项：类型/服务项/启用停用/C 端入口 A380→服务页）
IM_OPEN_PLATFORM_05_AUTHORIZATION_GOVERNANCE_DRAFT.md  授权治理待规范（审查结论/环境隔离/M0-M2 实施范围）
IM_OPEN_PLATFORM_06_REVIEW_SOP.md  申请与审核 SOP（IM 后台「第三方接入」页操作、判定标准、驳回话术、密钥交付/重置/吊销）
```

## 版本与分支（v2.0.0）

- 本次二阶段方案为**大版本更新，版本号统一升级到 2.0.0**（所有仓库）。
- 发布流程：当前所有仓库先合并主干并提交，再从主干建立新的大版本开发分支 **`develop/2.0.0-saas-20260826`**，二阶段工作在该开发分支进行。App 与桌面端发版版本同步从 **2.0.0** 起步；桌面端业务逻辑参照 App 端已敲定的服务逻辑实现。

> v2 变更：①IM 与 SaaS 独立产品化——IM 聊天仅作 C 端入口（授权登录 OAuth + 用户信息同步），两者数据库独立、代码独立、独立部署、可独立销售，公共规范/框架/依赖抽为公共结构共享；②首发业态收敛为 **KTV**，酒店/足浴降为后续演进；③确立通用支撑域（`common-services/`、`common-*`，`common-media-service`→`common-media-service`，支付域=支付业务+支付渠道，下探一层）；④公共 SDK 目录 `sdk/`→`sdk/`。
