package com.gvchat.protocol.mq.event;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 用户资料变更事件（IM -> SaaS 推送，最小化载荷，手机号脱敏，不含敏感原文）。 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileChangedEvent {
  private String eventId;
  private Long userId;
  private String openId;
  private String nickname;
  private String avatar;
  private String phone;
  private Instant occurredAt;
}
