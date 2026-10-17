package io.openware.im.conversation.domain.secretchat.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 私密聊天聚合根：一对一的 E2EE 会话，承载握手状态机与销毁策略。
 *
 * <p><b>为什么不带审计列</b>（standards/10 §聚合装配与持久化契约）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息，由适配器在写库时确定；响应需要的值经
 * {@code SecretChatRepository.Persisted} 的 {@code AuditSnapshot} 单独带出。
 * 因此本类的方法也**不再接收 {@code operatorId}/{@code occurredAt}**——审计值在持久化那一刻确定，
 * 领域方法不需要认识"操作人"这个概念（这正是改造前的错位：审计参数一路穿过业务签名）。
 */
public class SecretChat {
  private final Long id;
  private final Long userA;
  private final Long userB;
  private final String status;
  private final String safeCode;
  private final String destroyPolicy;
  private final String userAPublicKey;
  private final String userBPublicKey;
  private final String handshakeState;

  private SecretChat(Long id, Long userA, Long userB, String status, String safeCode, String destroyPolicy,
      String userAPublicKey, String userBPublicKey, String handshakeState) {
    this.id = id;
    this.userA = userA;
    this.userB = userB;
    this.status = status;
    this.safeCode = safeCode;
    this.destroyPolicy = destroyPolicy;
    this.userAPublicKey = userAPublicKey;
    this.userBPublicKey = userBPublicKey;
    this.handshakeState = handshakeState;
  }

  public static SecretChat create(long userA, long userB) {
    return new SecretChat(null, userA, userB, "handshake", null, "off", null, null, "pending");
  }

  /** 创建时预填双方公钥（发起方本端公钥 + 对端服务端已注册设备公钥），双方齐备即 ready。 */
  public static SecretChat createWithKeys(long userA, long userB, String userAPublicKey, String userBPublicKey) {
    boolean aKey = userAPublicKey != null && !userAPublicKey.isBlank();
    boolean bKey = userBPublicKey != null && !userBPublicKey.isBlank();
    boolean ready = aKey && bKey;
    return new SecretChat(null, userA, userB, ready ? "ready" : "handshake",
        ready ? computeSafeCode(userAPublicKey, userBPublicKey) : null, "off",
        aKey ? userAPublicKey : null, bKey ? userBPublicKey : null,
        ready ? "ready" : "pending");
  }

  /** 提交本端公钥参与握手；双方公钥齐备后进入 ready 并计算安全码指纹。 */
  public SecretChat submitHandshake(long userId, String publicKey) {
    String aKey = userAPublicKey;
    String bKey = userBPublicKey;
    if (userA != null && userA == userId) {
      aKey = publicKey;
    } else {
      bKey = publicKey;
    }
    boolean ready = aKey != null && bKey != null;
    return new SecretChat(id, userA, userB, ready ? "ready" : status,
        ready ? computeSafeCode(aKey, bKey) : safeCode,
        destroyPolicy, aKey, bKey, ready ? "ready" : handshakeState);
  }

  public boolean isParticipant(long userId) {
    return (userA != null && userA == userId) || (userB != null && userB == userId);
  }

  /** 更新销毁策略（应用层已校验取值白名单）。 */
  public SecretChat withDestroyPolicy(String policy) {
    return new SecretChat(id, userA, userB, status, safeCode, policy,
        userAPublicKey, userBPublicKey, handshakeState);
  }

  /** 由持久化对象重建领域对象（仅适配器使用）。 */
  public static SecretChat restore(Long id, Long userA, Long userB, String status, String safeCode,
      String destroyPolicy, String userAPublicKey, String userBPublicKey, String handshakeState) {
    return new SecretChat(id, userA, userB, status, safeCode, destroyPolicy, userAPublicKey, userBPublicKey,
        handshakeState);
  }

  private static String computeSafeCode(String publicKeyA, String publicKeyB) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest((publicKeyA + ":" + publicKeyB).getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 8; i++) {
        sb.append(String.format("%02X", digest[i]));
        if (i < 7) {
          sb.append(" ");
        }
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  public Long getId() { return id; }
  public Long getUserA() { return userA; }
  public Long getUserB() { return userB; }
  public String getStatus() { return status; }
  public String getSafeCode() { return safeCode; }
  public String getDestroyPolicy() { return destroyPolicy; }
  public String getUserAPublicKey() { return userAPublicKey; }
  public String getUserBPublicKey() { return userBPublicKey; }
  public String getHandshakeState() { return handshakeState; }
}
