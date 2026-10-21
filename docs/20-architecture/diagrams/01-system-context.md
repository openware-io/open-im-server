# 01 系统上下文图

[中文](01-system-context.md) | [English](01-system-context.en.md)

> 视角：C4 Level 1 — System Context  
> 回答：谁在使用 OpenChat？系统边界外有哪些外部系统？  
> 不回答：内部服务、数据库、类结构。

```mermaid
flowchart LR
  subgraph Actors["参与者"]
    C["C 端用户<br/>App / Desktop / H5"]
    O["运营、门店与服务人员<br/>IM Admin / SaaS Admin"]
    A["平台管理员<br/>租户 / 权限 / 审计"]
    D["开放平台开发者<br/>第三方应用"]
  end

  subgraph OpenChat["OpenChat 系统边界"]
    PLATFORM["OpenChat<br/>IM + 多租户 SaaS<br/>REST / WebSocket"]
  end

  subgraph External["外部系统"]
    PUSH["推送服务<br/>APNs / FCM / JPush"]
    SMS["短信服务"]
    PAY["支付渠道<br/>微信 / 支付宝 / 其他"]
    IDP["企业 IdP / OAuth"]
    OBS["监控与告警"]
  end

  C -->|"HTTPS / WSS"| PLATFORM
  O -->|"HTTPS"| PLATFORM
  A -->|"HTTPS"| PLATFORM
  D -->|"OAuth2 / OpenAPI"| PLATFORM

  PLATFORM -->|"推送"| PUSH
  PLATFORM -->|"验证码 / 通知"| SMS
  PLATFORM -->|"支付 / 退款"| PAY
  PLATFORM -->|"OAuth / OIDC"| IDP
  PLATFORM -->|"指标 / 日志 / 告警"| OBS
```

## 读图要点

- OpenChat 是一个系统边界，内部由 IM 域、SaaS 平台域和公共能力域组成；内部拆分见 [容器图](02-container.md)。
- C 端通过 HTTPS 调 REST，通过 WSS 保持 IM 长连接。
- 运营后台、SaaS 后台、开放平台都从统一入口进入，不直连内部服务。
- 对象存储是**系统内部能力**（MinIO + `common-media-service`），不画成外部系统。
- 外部系统只画真正跨组织边界的依赖，不把 Redis/MySQL 这类内部基础设施放进上下文图。

## 边界规则

1. 外部系统只通过明确协议接入，不共享数据库。
2. 第三方应用通过 OpenAPI/OAuth，不直接访问内部服务。
3. 推送、短信、支付失败必须有降级或重试，不阻塞主业务事务。
4. 企业 IdP 是可选能力；本地账号体系仍可独立运行。

## 相关实现

- 统一入口：[ingress.yaml](../../../k8s/local/ingress.yaml)
- 推送：[PUSH_SETUP.md](../../60-deployment/PUSH_SETUP.md)
- 开放平台契约：[contracts](../../30-reference/contracts/README.md)
