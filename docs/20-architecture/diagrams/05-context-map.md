# 05 限界上下文图

[中文](05-context-map.md) | [English](05-context-map.en.md)

> 视角：DDD Context Map  
> 回答：有哪些限界上下文？谁依赖谁？跨上下文通过什么协作？  
> 不回答：部署副本、数据库表结构。

```mermaid
flowchart LR
  subgraph Edge["接入与门户"]
    GW["gateway"]
    WS["im-access-ws"]
    PORTAL["unified-portal"]
  end

  subgraph IM["IM 业务域"]
    USER["用户上下文"]
    CONV["会话上下文"]
    MSG["消息上下文"]
    IMADM["IM 管理读模型"]
  end

  subgraph SaaS["SaaS 平台域"]
    ID["身份上下文"]
    TEN["租户上下文"]
    CUS["客户/会员上下文"]
    ORD["订单/预约上下文"]
    RES["资源/库存上下文"]
    MKT["营销上下文"]
    BFF["平台管理 BFF"]
  end

  subgraph Shared["公共能力域"]
    MEDIA["媒体"]
    AUD["审计"]
    PAY["支付"]
    PAYCH["支付渠道"]
    SMS["短信/邮件"]
    IDAAS["集团 IAM"]
  end

  GW -->|"REST"| IM
  GW -->|"REST"| SaaS
  WS -->|"命令"| MSG
  PORTAL -->|"OAuth / Session"| ID

  CONV -->|"会话授权事件"| MSG
  USER -->|"好友/资料事件"| MSG
  IMADM -.->|"读投影"| MSG
  IMADM -.->|"读投影"| CONV

  BFF -->|"聚合"| ID
  BFF -->|"聚合"| TEN
  BFF -->|"聚合"| ORD
  ORD -->|"房间/库存"| RES
  ORD -->|"客户/会员"| CUS
  ORD -->|"收款/退款"| PAY
  ORD -.->|"订单事件"| AUD
  MKT -->|"优惠券/积分"| CUS
  MKT -.->|"订单事件"| ORD
  ID -->|"OIDC / 企业登录"| IDAAS
  PAY -->|"渠道配置"| PAYCH

```

## 上下文关系

| 上游 | 下游 | 关系 | 规则 |
| --- | --- | --- | --- |
| Gateway / WS | IM、SaaS | 同步 API / 命令 | 接入层只做协议与鉴权，不承载领域规则 |
| 用户、会话 | 消息 | 领域事件 | 好友/会话变更通过事件驱动消息侧投影 |
| 订单/预约 | 资源、客户、支付、审计 | 同步 API / 事件 | 订单不直接写资源/客户/支付表 |
| 平台管理 BFF | 身份、租户、订单 | 聚合查询 | BFF 不拥有核心业务数据 |
| 身份 | 集团 IAM | OIDC/OAuth | 企业 IdP 可选，本地账号兜底 |
| IM / SaaS | 公共能力 | API / 事件 | 公共能力不得反向依赖具体业务域 |

## 边界规则

1. 上下文之间只通过 API 或事件协作，不共享表、不跨库 JOIN。
2. 订单是预约/开台的编排者，但不是资源、客户、支付数据的 Owner。
3. IM 管理读模型只读投影，不作为 IM 业务事实的写入方。
4. 公共能力是下游；不能被 IM/SaaS 反向依赖。
5. 接入层是边缘适配器，不能成为业务编排层。

## 相关文档

- [DDD 服务工程规范](../../80-development/standards/10_DDD_SERVICE_ENGINEERING_CONVENTIONS.md)
- [数据归属](04-data-ownership.md)
- [服务目录](../../50-services/README.md)
