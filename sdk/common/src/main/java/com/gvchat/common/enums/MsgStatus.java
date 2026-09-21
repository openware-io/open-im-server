package com.gvchat.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum MsgStatus {
  /*
   */
  SENT("sent"),
  DELIVERED("delivered"),
  READ("read"),
  RECALLED("recalled"),
  ;

  private final String value;

  MsgStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static MsgStatus fromValue(String v) {
    for (MsgStatus e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown MsgStatus: " + v);
  }
}
