package com.gvchat.common.audit;

import com.gvchat.common.util.SnowflakeIdGenerator;
import com.gvchat.infrastructure.audit.AuditClientConfig;
import com.gvchat.infrastructure.security.InternalServiceAuthentication;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 统一审计服务启动类。
 *
 * <p>{@code @Import} 内部服务鉴权所需 Bean：{@code /internal/audit/**} 上报通道必须校验 HMAC 签名，
 * 不能像早期占位实现那样对内部端点裸奔（任何能访问 4190 的进程都能伪造审计记录）。
 *
 * <p>另 {@code @Import} 雪花 ID 生成器：审计主键改为应用侧生成（多行批插要插入前就知道 ID，
 * 见 {@code AuditIdGenerator}）；它在 {@code sdk/common}，不在本服务的扫描包下，必须显式导入。
 *
 * <p>{@code @Import(AuditClientConfig)}：本服务自己也要上报审计（保留任务的归档登记与分区预建，
 * 见 {@code AuditRetentionApplicationService}），依赖 SDK 的 {@code AuditClient}。该 Bean 不在本服务的
 * 扫描包下——漏掉它会让 {@code auditRetentionApplicationService} 构造注入失败、服务直接启动不起来。
 *
 * <p>{@code @EnableScheduling}：启用审计保留任务（分区预建、归档清单、幂等台账清理，
 * 见 {@code AuditRetentionJob}）。保留策略必须在服务内自驱，不能依赖外部脚本按月上人。
 */
@SpringBootApplication
@EnableScheduling
@Import({
    InternalServiceAuthenticationProperties.class,
    InternalServiceAuthentication.class,
    AuditClientConfig.class,
    SnowflakeIdGenerator.class
})
public class CommonAuditApplication {
    public static void main(String[] args) { SpringApplication.run(CommonAuditApplication.class, args); }
}
