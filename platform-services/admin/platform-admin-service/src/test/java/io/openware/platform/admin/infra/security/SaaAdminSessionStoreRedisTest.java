package io.openware.platform.admin.infra.security;

import io.openware.infrastructure.security.JwtProperties;
import io.openware.platform.admin.domain.model.AdminRole;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "SAAS_TEST_REDIS_PORT", matches = "[0-9]+")
class SaaAdminSessionStoreRedisTest {
    private LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private SaaAdminSessionStore sessions;
    private String sessionId;

    @BeforeEach
    void setup() {
        var configuration = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv("SAAS_TEST_REDIS_PORT")));
        String password = System.getenv("SAAS_TEST_REDIS_PASSWORD");
        if (password != null) configuration.setPassword(password);
        connection = new LettuceConnectionFactory(configuration);
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        sessions = new SaaAdminSessionStore(redis, new AdminTenantContextTokenSigner(jwtProperties()));
        sessionId = sessions.createSession(new AdminContext(1L, "context-regression", "Test",
                AdminRole.SUPER_ADMIN, 1L, null), Duration.ofMinutes(2));
    }

    @AfterEach
    void cleanup() {
        if (sessionId != null) {
            sessions.deleteSession(sessionId);
            assertFalse(Boolean.TRUE.equals(redis.hasKey("saas-admin:session:" + sessionId)));
        }
        connection.destroy();
    }

    /** 平台作用域 token 的签发方：测试密钥满足 JwtProperties 的最小长度约束。 */
    private static JwtProperties jwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("saas-admin-session-store-test-secret-0123456789");
        return properties;
    }

    @Test
    void selectionAndTokenChangeTogetherWithoutExtendingLoginLifetime() {
        long before = redis.getExpire("saas-admin:session:" + sessionId, TimeUnit.MILLISECONDS);
        sessions.setActiveContext(sessionId, "token-a", "100::");
        assertEquals("100::", sessions.selectedContextId(sessionId));
        assertEquals("token-a", sessions.findSession(sessionId).orElseThrow().tenantContextToken());
        sessions.setActiveContext(sessionId, "token-b", "200:201:202");
        assertEquals("200:201:202", sessions.selectedContextId(sessionId));
        assertEquals("token-b", sessions.findSession(sessionId).orElseThrow().tenantContextToken());
        assertEquals("context-regression", sessions.findSession(sessionId).orElseThrow().username());
        long after = redis.getExpire("saas-admin:session:" + sessionId, TimeUnit.MILLISECONDS);
        assertTrue(after > 0 && after <= before);
    }

    @Test
    void logoutCannotBeUndoneByContextUpdate() {
        sessions.deleteSession(sessionId);
        assertThrows(IllegalArgumentException.class, () -> sessions.setActiveContext(sessionId, "token", "100::"));
        assertTrue(sessions.findSession(sessionId).isEmpty());
    }

    @Test
    void expiredSessionCannotBeRecreatedByContextUpdate() {
        redis.expire("saas-admin:session:" + sessionId, Duration.ZERO);
        assertThrows(IllegalArgumentException.class, () -> sessions.setActiveContext(sessionId, "token", "100::"));
        assertNull(sessions.selectedContextId(sessionId));
    }
}
