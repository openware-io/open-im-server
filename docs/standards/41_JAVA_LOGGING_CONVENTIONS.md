# Java 日志规范

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
3. 发布前统一通过 `release_manager/verify.bat` 执行校验。
