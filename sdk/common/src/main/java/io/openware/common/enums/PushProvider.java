package io.openware.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum PushProvider {
  /*
   */
  APNS("apns"),
  FCM("fcm"),
  JPUSH("jpush"),
  ;

  private final String value;

  PushProvider(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static PushProvider fromValue(String v) {
    for (PushProvider e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown PushProvider: " + v);
  }
}
