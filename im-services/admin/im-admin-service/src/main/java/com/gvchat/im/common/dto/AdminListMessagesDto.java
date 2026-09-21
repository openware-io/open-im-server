package com.gvchat.common.dto;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.MsgStatus;
import com.gvchat.common.enums.MsgType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminListMessagesDto {
  private String keyword;
  private ChatType chatType;
  private MsgType msgType;
  private MsgStatus status;
  private Long fromUserId;
  private Integer page;
  private Integer pageSize;
}
