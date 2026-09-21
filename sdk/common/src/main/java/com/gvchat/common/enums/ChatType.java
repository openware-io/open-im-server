package com.gvchat.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

/** 聊天会话类型区分聊与群聊*/
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum ChatType {
  /*
   */
  PRIVATE("private"),
  GROUP("group"),
  CHANNEL("channel"),
  SECRET("secret"),
  SECRET_GROUP("secret_group"),
  ;

  private final String value;

  ChatType(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static ChatType fromValue(String v) {
    for (ChatType e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown ChatType: " + v);
  }
}
