# Java 日志规范

[中文](41_JAVA_LOGGING_CONVENTIONS.md) | [English](41_JAVA_LOGGING_CONVENTIONS.en.md)

## 目标与适用范围

**目标**：让生产日志可检索、可归因、可告警——每条日志都能回答"谁、做了什么、结果如何"，并能按标识字段串联同一次业务操作。

**适用范围**：Java 生产代码（服务、网关、消费者、适配器、投影器、过滤器）的日志器声明、文案、级别与异常处理。

**非目标**：不规定日志的落盘位置、保留期与诊断产物目录结构（见 [42_LOGGING_AND_OUTPUTS.md](42_LOGGING_AND_OUTPUTS.md)）；不规定指标与链路追踪（见 [40_OBSERVABILITY_CONVENTIONS.md](40_OBSERVABILITY_CONVENTIONS.md)）。

## 术语与前置假设

| 术语 | 定义 |
| --- | --- |
| 日志器声明 | 取得日志对象的写法。本规范只允许 Lombok `@Slf4j` 生成。 |
| 标识字段 | 能串联同一业务操作的技术关键字，如 `msgId`、`conversationId`、`commandId`、`eventId`。 |
| 业务上下文 | 失败时应能定位"哪条业务、哪个对象、哪个租户"的信息；只输出异常对象不算上下文。 |
| 主链路里程碑 | 关键输入、关键输出、状态切换等即使在高并发下也必须可见的节点。 |
| 静默异常 | 捕获后既不记录也不重抛，导致失败无迹可循的处理方式。 |

**前置假设**：项目已启用统一日志门面（SLF4J）；日志采集能按字段检索；`DEBUG` 在生产默认关闭。

## 原则与允许禁止例外

- **允许**：用中文描述业务语义；对技术关键字保留英文原样；用占位符 `{}` 传参而不是字符串拼接。
- **禁止**：
  1. 直接导入 `org.slf4j.Logger`、`org.slf4j.LoggerFactory`；
  2. 手写 `private static final Logger log = LoggerFactory.getLogger(...)`；
  3. 把日志写成难以检索的随意短句；
  4. 只输出异常对象而不写业务上下文；
  5. 用 `System.out` / `System.err` / `printStackTrace()` 代替日志。
- **例外**：无。以上为强制项，不设项目级豁免；如有历史存量，按改造方案分批清零，不以例外长期保留。

## 声明规范

1. Java 生产代码统一使用 Lombok `@Slf4j` 声明日志器。
2. 禁止直接导入 `org.slf4j.Logger`、`org.slf4j.LoggerFactory`。
3. 禁止手写 `private static final Logger log = LoggerFactory.getLogger(...)`。
4. 新增或修改的服务类、网关类、消费者、适配器、投影器、过滤器都必须遵守这一规则。

## 文案规范

1. 日志文案允许使用中文，且关键业务日志优先使用中文描述业务语义。
2. 标识字段、协议字段、异常类型、配置键等技术关键字保留英文，例如 `msgId`、`conversationId`、`commandId`、`eventId`。
3. 推荐格式：`中文动作说明, key1={}, key2={}`。
4. 禁止把日志写成难以检索的随意短句，也禁止只输出异常对象不写业务上下文。

## 级别规范

1. `INFO`：关键输入、关键输出、状态切换、主链路里程碑。
2. `WARN`：可恢复异常、降级、重复请求、非法输入、权限拒绝。
3. `ERROR`：链路失败、关键依赖不可用、补偿失败、数据不一致风险。
4. `DEBUG`：仅用于本地排障的高频明细，不能依赖 `DEBUG` 才能看懂主链路。

## 校验门禁

1. `scripts/validate/validate-java-logging-conventions.ps1` 会拦截手写日志器声明。
2. `scripts/validate/validate-java-exception-logging.ps1` 会拦截静默异常或无上下文异常处理。
3. 发布前统一通过[统一工程校验入口](../governance/ENGINEERING_RULES.md)执行校验（`scripts/validate/invoke-engineering-validation.ps1`）。

## 日志写法模板

**推荐（示例，不构成强制措辞）**：

```java
@Slf4j
public class MessageSendApplicationService {
    public void send(SendCommand cmd) {
        log.info("发送消息开始, msgId={}, conversationId={}, senderId={}",
                 cmd.msgId(), cmd.conversationId(), cmd.senderId());
        try {
            // ... 业务
            log.info("发送消息成功, msgId={}, costMs={}", cmd.msgId(), cost);
        } catch (RetryableException e) {
            log.warn("发送消息可重试失败, msgId={}, reason={}", cmd.msgId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("发送消息失败, msgId={}, conversationId={}", cmd.msgId(), cmd.conversationId(), e);
            throw e;
        }
    }
}
```

**反例**：`log.info("ok")`、`log.error(e.getMessage())`、`log.error("失败", e)`（无业务上下文）、字符串拼接 `log.info("msgId=" + id)`。

## 可验证的验收标准

| # | 判据 | 证据形式 |
| --- | --- | --- |
| 1 | 无手写日志器声明；生产代码全部经 `@Slf4j` | 日志声明门禁通过 |
| 2 | 无静默异常；每个 `catch` 要么记录要么重抛 | 异常日志门禁通过 |
| 3 | 无 `System.out` / `System.err` / `printStackTrace()` | 同上（静态扫描） |
| 4 | 关键输入/输出/状态切换各有 `INFO`：给出一条真实调用链的日志片段 | 联调或回归日志 |
| 5 | 失败日志含标识字段与业务上下文：给出一次故障的日志片段 | 排障记录 |
| 6 | 主链路不依赖 `DEBUG` 才能看懂 | 生产默认级别下的日志审阅 |

**完成定义**：上表逐项有可复现证据，且统一工程校验入口在该范围退出码为 0。

## 与其他规范的关系

| 关系 | 文档 | 说明 |
| --- | --- | --- |
| 关联 | [40_OBSERVABILITY_CONVENTIONS.md](40_OBSERVABILITY_CONVENTIONS.md) | 日志是可观测性三信号之一；指标与追踪另见该文 |
| 依赖 | [42_LOGGING_AND_OUTPUTS.md](42_LOGGING_AND_OUTPUTS.md) | 日志与诊断产物的落盘位置与目录约束 |
| 关联 | [30_TEXT_ENCODING_AND_BATCH_EDITING.md](30_TEXT_ENCODING_AND_BATCH_EDITING.md) | 中文日志的编码口径（UTF-8 无 BOM） |
| 引用 | [ENGINEERING_RULES.md](../governance/ENGINEERING_RULES.md) | 统一工程校验入口与项目级启用方式 |
