package io.openware.infrastructure.observability;

/**
 * 当前线程的关联标识（standards/40）。
 *
 * <p>HTTP 入口由 {@code TenantContextFilter} 从 {@code X-Request-Id} 写入，
 * MQ 生产者在发送时写入消息属性，消费者处理消息前恢复、处理完清理。
 * 这样跨 HTTP → MQ → 消费的日志可以用同一个 requestId 串联。
 */
public final class RequestIdContext {

  /** 跨边界使用的头/属性名。 */
  public static final String HEADER = "X-Request-Id";

  /** RocketMQ 用户属性名（避免用户属性名里出现分隔符）。 */
  public static final String PROPERTY = "requestId";

  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

  private RequestIdContext() {
  }

  /** 当前线程的关联标识；未设置时返回 {@code null}。 */
  public static String get() {
    return CURRENT.get();
  }

  /** 设置当前线程的关联标识。 */
  public static void set(String requestId) {
    CURRENT.set(requestId);
  }

  /** 清理当前线程，避免线程池复用串味。 */
  public static void clear() {
    CURRENT.remove();
  }
}
