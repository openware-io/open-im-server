package io.openware.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.openware.infrastructure.audit.AuditClientConfig;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * 启动类装配守卫：审计服务自己也要上报审计（保留任务的归档登记/分区预建），
 * 而 {@code AuditClient} 由 SDK 的 {@code AuditClientConfig} 提供，不在本服务的扫描包下。
 *
 * <p>这不是形式检查：漏掉 {@code @Import(AuditClientConfig.class)} 时，
 * {@code AuditRetentionApplicationService} 的构造注入会在启动阶段失败
 * （No qualifying bean of type 'AuditClient'），整个服务起不来——本地 Kind 回归就是这样抓到的。
 * 用例直接读注解，不需要拉起 Spring 上下文，因此不会因为数据库/中间件不可用而失效。
 */
class AuditClientWiringTest {

    @Test
    @DisplayName("启动类必须导入 AuditClientConfig，否则保留任务的自留审计注入失败")
    void applicationImportsAuditClientConfig() {
        Import imported = CommonAuditApplication.class.getAnnotation(Import.class);

        assertThat(imported)
                .as("CommonAuditApplication 必须声明 @Import(AuditClientConfig.class)")
                .isNotNull();
        List<Class<?>> types = Arrays.asList(imported.value());
        assertThat(types)
                .as("缺少 AuditClientConfig 会让 auditRetentionApplicationService 无法构造")
                .contains(AuditClientConfig.class);
    }
}
