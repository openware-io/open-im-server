package com.gvchat.protocol.ws.dto;

import com.gvchat.common.enums.ChatType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * WebSocket 正在输入事件载荷（{@code chat:typing}）。
 *
 * <p>用于让客户端在会话中展示“对方正在输入”提示。服务端通常只做转发，不做持久化。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WsTypingDto {
  @NotBlank
  private String toId;
  @NotNull
  private ChatType chatType;
}

