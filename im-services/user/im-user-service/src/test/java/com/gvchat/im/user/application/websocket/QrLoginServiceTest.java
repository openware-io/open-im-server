package com.gvchat.im.user.application.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.constant.RedisKeys;
import com.gvchat.im.user.application.websocket.QrLoginService.QrLoginState;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class QrLoginServiceTest {
  @Mock
  private StringRedisTemplate redis;
  @Mock
  private ValueOperations<String, String> valueOps;
  private QrLoginService service;

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(valueOps);
    service = new QrLoginService(redis);
  }

  private static String key(String token) {
    return RedisKeys.QR_LOGIN + token;
  }

  @Test
  void createSessionStoresPendingAndReturnsToken() {
    String token = service.createSession();
    assertThat(token).isNotBlank();
    verify(valueOps).set(anyString(), eq("pending"), any(Duration.class));
  }

  @Test
  void confirmMarksConfirmedWithUserId() {
    when(valueOps.get(key("tok"))).thenReturn("pending");
    assertThat(service.confirm("tok", 42L)).isTrue();
    verify(valueOps).set(eq(key("tok")), eq("confirmed:42"), any(Duration.class));
  }

  @Test
  void confirmOnMissingSessionReturnsFalse() {
    when(valueOps.get(key("missing"))).thenReturn(null);
    assertThat(service.confirm("missing", 42L)).isFalse();
  }

  @Test
  void pollTransitionsPendingExpiredConfirmed() {
    when(valueOps.get(key("pending"))).thenReturn("pending");
    assertThat(service.poll("pending").status()).isEqualTo("pending");

    when(valueOps.get(key("missing"))).thenReturn(null);
    assertThat(service.poll("missing").status()).isEqualTo("expired");

    when(valueOps.get(key("confirmed"))).thenReturn("confirmed:42");
    QrLoginState state = service.poll("confirmed");
    assertThat(state.status()).isEqualTo("confirmed");
    assertThat(state.userId()).isEqualTo(42L);
    verify(redis).delete(key("confirmed"));
  }
}
