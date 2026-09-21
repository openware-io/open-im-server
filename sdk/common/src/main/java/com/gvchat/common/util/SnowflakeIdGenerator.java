package com.gvchat.common.util;

import org.springframework.stereotype.Component;

/**
 * 基于 Twitter Snowflake 算法的分布式唯一 ID 生成器。
 * <p>
 * ID 结构：时间戳（高位）+ 工作节点 ID + 序列号，最终输出无符号十进制字符串。
 * </p>
 */
@Component
public class SnowflakeIdGenerator {
  /** 自定义纪元起点（毫秒），用于缩短时间戳占用位*/
  private static final long EPOCH = 1700000000000L;
  /** 工作节点 ID 占用位数 */
  private static final long WORKER_BITS = 10L;
  /** 同一毫秒内的序列号占用位*/
  private static final long SEQ_BITS = 12L;
  /** 序列号最大值（12 位全 1*/
  private static final long MAX_SEQ = (1L << SEQ_BITS) - 1L;

  private final long workerId;
  private long sequence = 0L;
  private long lastTimestamp = -1L;

  /**
   * 构造生成器，随机分10 位工作节ID。
   */
  public SnowflakeIdGenerator() {
    this.workerId = (long) (Math.random() * 1024) & ((1L << WORKER_BITS) - 1);
  }

  /**
   * 生成下一个全局唯一 ID 字符串。
   *
   * @return 无符号十进制形式的唯一 ID
   */
  public synchronized String nextId() {
    // 步骤 1：获取当前毫秒时间戳
    long now = System.currentTimeMillis();
    if (now == lastTimestamp) {
      // 步骤 2a：同一毫秒内，序列号自增并取模，防止溢。
      sequence = (sequence + 1) & MAX_SEQ;
      if (sequence == 0) {
        // 步骤 2b：序列号用尽，自旋等待下一毫秒
        while (now <= lastTimestamp) {
          now = System.currentTimeMillis();
        }
      }
    } else {
      // 步骤 3：新的毫秒窗口，序列号重置为 0
      sequence = 0L;
    }
    lastTimestamp = now;
    // 步骤 4：按位拼—时间差左移、工作节ID 左移、与序列号按位或
    long id = ((now - EPOCH) << (WORKER_BITS + SEQ_BITS)) | (workerId << SEQ_BITS) | sequence;
    // 步骤 5：转为无符号十进制字符串返回
    return Long.toUnsignedString(id);
  }
}
