# S7 本地发布门禁

> 2026-10-25
> 范围：本地 Kind `open-im-local`
> 当前：S7 cash-only E2E CONDITIONAL PASS

## 1. 发布方式

```text
build-saas-release.ps1 生成 schema v2 manifest
k8s-scoped.ps1 按 manifest 部署到 Kind
禁止 kubectl set image / 手工 tag
禁止 latest
```

ACR、ACK、云生产发布不在本项目范围，相关配置不参与验证、不写入文档。

## 2. 本地发布验收

```text
1. 工作树干净
2. 目标服务镜像 tag 与 manifest 一致
3. Kind 内 Pod Ready
4. 新 Pod 启动后 health/readiness 通过
5. 核心业务 E2E 通过
6. 回滚到上一 manifest 后 Pod Ready、核心 E2E 仍通过
```

## 3. 本轮验证结果

```text
本地全量 manifest 发布：PASS
本地后端 + 前端回滚：PASS
B/C/SaaS 后台真实页面无 4xx/5xx/JS error：PASS
Android 测试 APK 真机预订 + B 收款：PASS
异常处理门禁：PASS
```

## 3.1 范围说明

```text
本地 Kind 使用测试 APK；正式签名 APK 不在本次本地范围
其他业务页 action 级全量不属于 S7 酒店 cash-only，另列 P2
text-encoding PASS（损坏历史归档按原字节隔离并记录 SHA-256）
service-api-boundaries 仍有 6 条既有分层基线（2 个 controller 已修，5 个 application + 1 个 controller 待迁移）
详见 evidence/engineering-gates-final.txt
```

## 4. 禁止

```text
用旧镜像冒充当前提交
跳过 Pod Ready / health
用 debug 签名冒充正式签名
mock 在线支付或异常响应
```
