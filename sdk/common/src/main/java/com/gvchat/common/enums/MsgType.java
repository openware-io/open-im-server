package com.gvchat.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

/** 消息内类型*/
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum MsgType {
  /*
   */
  TEXT("text"),
  IMAGE("image"),
  FILE("file"),
  VOICE("voice"),
  VIDEO("video"),
  LOCATION("location"),
  NAMECARD("namecard"),
  CALL("call"),
  SYSTEM("system"),
  RECALL("recall"),
  EMOJI("emoji"),
  ;

  private final String value;

  MsgType(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static MsgType fromValue(String v) {
    for (MsgType e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown MsgType: " + v);
  }
}
