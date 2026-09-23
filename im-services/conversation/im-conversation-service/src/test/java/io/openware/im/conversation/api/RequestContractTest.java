package io.openware.im.conversation.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * 前后端请求契约测试（与前端 `im_api_contract_test.dart` 对应）。
 *
 * <p>前端手写请求体依赖本包 DTO 的字段名与校验注解；任一字段改名或漏注解，
 * 都会造成前端发「字段名对不上」的请求（如 userB/peerUserId、policy/destroyPolicy 事故）。
 * 本测试锁定字段名与必填注解，防止后端侧无意破坏契约。
 */
class RequestContractTest {

  private static final Class<?>[] CONTRACTS = {
    io.openware.im.conversation.api.secretchat.CreateSecretChatRequest.class,
    io.openware.im.conversation.api.secretchat.SubmitHandshakeRequest.class,
    io.openware.im.conversation.api.secretchat.SetDestroyPolicyRequest.class,
    io.openware.im.conversation.api.channel.CreateChannelRequest.class,
  };

  @Test
  void createSecretChatRequestMustKeepUserBField() throws Exception {
    Class<?> type = io.openware.im.conversation.api.secretchat.CreateSecretChatRequest.class;
    Field userB = type.getDeclaredField("userB");
    assertTrue(userB.isAnnotationPresent(NotNull.class),
        "userB 必须有 @NotNull，否则前端 userB=null 时不会报 must not be blank");
  }

  @Test
  void setDestroyPolicyRequestMustKeepPolicyField() throws Exception {
    Class<?> type = io.openware.im.conversation.api.secretchat.SetDestroyPolicyRequest.class;
    Field policy = type.getDeclaredField("policy");
    assertTrue(policy.isAnnotationPresent(NotBlank.class),
        "policy 必须有 @NotBlank，前端发送 {policy: ...} 依赖该字段名");
  }

  @Test
  void submitHandshakeRequestMustKeepPublicKeyField() throws Exception {
    Class<?> type = io.openware.im.conversation.api.secretchat.SubmitHandshakeRequest.class;
    Field publicKey = type.getDeclaredField("publicKey");
    assertTrue(publicKey.isAnnotationPresent(NotBlank.class));
  }

  @Test
  void createChannelRequestMustKeepNameField() throws Exception {
    Class<?> type = io.openware.im.conversation.api.channel.CreateChannelRequest.class;
    Field name = type.getDeclaredField("name");
    assertTrue(name.isAnnotationPresent(NotBlank.class));
  }

  @Test
  void allContractTypesHaveRequiredGetters() {
    // 确保 DTO 未被误改成 record 或缺 getter（Lombok @Getter 生成 getXxx）。
    for (Class<?> type : CONTRACTS) {
      assertNotNull(type, "contract type must exist");
      assertTrue(Arrays.stream(type.getDeclaredMethods())
          .anyMatch(m -> m.getName().startsWith("get")), type.getSimpleName() + " 应暴露 getter");
    }
  }
}
