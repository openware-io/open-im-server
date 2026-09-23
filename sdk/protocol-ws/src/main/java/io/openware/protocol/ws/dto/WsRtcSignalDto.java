package io.openware.protocol.ws.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * WebSocket RTC 信令事件载荷（{@code rtc:signal}）。
 *
 * <p>该 DTO 用于在客户端之间转发 WebRTC 建联过程中的 SDP/ICE Candidate 等信令数据。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WsRtcSignalDto {
  @NotBlank
  private String action;
  @NotNull
  private Long targetUserId;
  private String mediaType;
  private Object sdp;
  private Object candidate;
  private String callId;
}

