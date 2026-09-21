package com.gvchat.common.audit.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gvchat.infrastructure.security.InternalServiceAuthentication;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 回归：审计服务的内部鉴权 Bean 只能有一个 {@link InternalServiceAuthenticationProperties}。
 *
 * <p>线上事故形态：{@code AuditWebConfig} 曾额外标注
 * {@code @EnableConfigurationProperties(InternalServiceAuthenticationProperties.class)}，而该 properties 类
 * 自带 {@code @Component}、启动类又 {@code @Import} 过一次（bean 名 = 类全限定名），于是容器里出现两个同类型 Bean
 * （{@code internal.service-auth-<FQN>} 与 {@code <FQN>}），{@link InternalServiceAuthentication} 构造器注入
 * 直接失败，审计服务在 kind/ACK 上启动即崩（{@code No qualifying bean ... expected single matching bean but found 2}）。
 *
 * <p>本用例用 {@link ApplicationContextRunner} 复现「properties 已被注册」的前提再加载 Web 配置：
 * 只要配置类再注册一次同类型 Bean，上下文就会失败或出现 2 个候选 Bean，从而在单测阶段拦住这类启动事故。
 */
class AuditWebConfigContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AuditWebConfig.class)
            // 模拟启动类的 @Import 与 @Component 扫描：properties 已作为 Bean 存在，且已按运行期配置就绪。
            .withBean(InternalServiceAuthenticationProperties.class, AuditWebConfigContextTest::configuredProperties)
            // 真正会因「两个候选 Bean」而注入失败的消费方。
            .withBean(InternalServiceAuthentication.class)
            .withPropertyValues("jwt.secret=test-jwt-secret-at-least-32-characters-long");

    private static InternalServiceAuthenticationProperties configuredProperties() {
        InternalServiceAuthenticationProperties properties = new InternalServiceAuthenticationProperties();
        properties.setServiceName("common-audit-service");
        properties.setExpectedSource("gv-im-audit-reporter");
        properties.setSecret("test-internal-service-auth-secret-0123456789");
        return properties;
    }

    @Test
    void exposesExactlyOneInternalServiceAuthenticationPropertiesBean() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(InternalServiceAuthenticationProperties.class))
                    .as("内部鉴权属性 Bean 必须唯一：重复注册会让服务启动失败")
                    .hasSize(1);
            assertThat(context.getBeansOfType(InternalServiceAuthentication.class)).hasSize(1);
        });
    }
}
