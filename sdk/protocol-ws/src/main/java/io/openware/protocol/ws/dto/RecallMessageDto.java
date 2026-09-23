package io.openware.protocol.ws.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * WebSocket 撤回消息事件载荷。
 *
 * <p>用于客户端请求撤回指定消息。服务端会做权限与时效校验，并将撤回结果同步给会话参与方。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecallMessageDto {
  @NotBlank
  private String msgId;
}

