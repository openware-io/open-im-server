# 酒店原型查看指南

[中文](HOTEL_PROTOTYPE_GUIDE.md) | [English](HOTEL_PROTOTYPE_GUIDE.en.md)

> 状态：`draft`（`develop/2.4.0-hotel`）  
> 目的：让你在不接后端的情况下检查 C 端、B 端 Web、B 端移动前台原型。

## 1. 原型入口

| 端 | 仓库 | 入口 |
| --- | --- | --- |
| C 端 | `open-chat-app` | 服务中心 → 酒店（`/services/demo/hotel`） |
| B 端 Web | `open-saas-admin` | `/saas/#/admin/tenant/hotel`，左侧“酒店管理” |
| B 端移动前台 | `open-chat-app` | `/business/hotel/dashboard`，B 端登录页“酒店前台原型” |

B 端 Web 已按开发发布规范部署到本地 Kind：

```text
http://127.0.0.1:30080/saas/#/admin/tenant/hotel
版本：2.4.1-SNAPSHOT
```

该版本启用 `VITE_HOTEL_PROTOTYPE=true`，因此在后端菜单接入前会显示“酒店管理”入口。接入正式菜单后关闭该标志。

C 端和移动前台是 Flutter 客户端原型，不部署到上述 Web 系统；用 `flutter run` 查看。

全部数据为本地 mock，不调用酒店后端。

## 2. 运行

### B 端 Web

```powershell
cd open-saas-admin
npm install
npm run dev
```

打开 Vite 输出的地址，登录/选择租户后进入：

```text
/admin/tenant/hotel
```

开发环境会在侧栏追加“酒店管理”；生产环境不追加，等后端菜单正式下发。

### C 端 / 移动前台

```powershell
cd open-chat-app
flutter pub get
flutter run
```

移动前台：

```text
B 端登录页 → 酒店前台原型
或 /business/hotel/dashboard
```

## 3. 重点检查

### 接入与入口

- 集团内酒店 vs 单店；
- 销售入口：OpenChat 服务中心 / 休闲娱乐 / 集团内部；
- 门店级可见范围。

### 支付、押金、积分

- 预付 / 到店付 / 定金；
- 押金开关、金额、收取方式；
- 积分抵扣开关、最大抵扣比例；
- 币种 USD / CNY，默认 USD。

### 库存与房态

- 未来 7 天库存；
- 关房；
- 房态：可用 / 在住 / 待退 / 清扫 / 维修。

### 预订与前台

- 今日到店、在住、待退房；
- 办理入住：收押金、分配房号；
- 结账退房：押金扣减、退款；
- 订单列表与状态。

## 4. 已知限制

- 全部 mock，不写后端；
- 无真实库存并发、支付回调、退款；
- 无发票/公安登记；
- 移动前台尚未接 BFF；
- C 端订单中心跨集团/门店仍按 ADR-0009 设计，未接真实索引。

## 5. 相关

- [实现方案](HOTEL_IMPLEMENTATION_PLAN.md)
- [决策](HOTEL_DECISIONS.md)
- [设计审查](HOTEL_DESIGN_REVIEW.md)
- [计费与结账域](../billing/README.md)
