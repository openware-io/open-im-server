package com.gvchat.protocol.ws.dto;

import java.util.List;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 标记消息已读请求体，{@code MessageController} 使用*/
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReadReceiptDto {
  @NotNull
  private List<String> msgIds;
}

