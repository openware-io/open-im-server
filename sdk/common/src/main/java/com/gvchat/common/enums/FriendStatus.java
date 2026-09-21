package com.gvchat.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum FriendStatus {
  /*
   */
  NORMAL("normal"),
  BLOCKED("blocked"),
  ;

  private final String value;

  FriendStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static FriendStatus fromValue(String v) {
    for (FriendStatus e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown FriendStatus: " + v);
  }
}
