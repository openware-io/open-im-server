package com.gvchat.platform.customer;

import com.gvchat.common.crypto.AesGcmCipher;
import com.gvchat.common.util.SnowflakeIdGenerator;
import com.gvchat.infrastructure.audit.AuditClientConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * customer 域启动类。
 *
 * <p>{@code @EnableScheduling}：启用客户姓名盲索引的存量回填任务
 * （{@code MemberNameIndexRebuildJob}，默认 30 分钟一轮、可用
 * {@code member.name-index.rebuild-enabled=false} 关闭）。
 */
@SpringBootApplication
@EnableScheduling
@Import({AuditClientConfig.class, SnowflakeIdGenerator.class, AesGcmCipher.class})
public class PlatformCustomerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformCustomerApplication.class, args);
    }
}
