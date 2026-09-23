package io.openware.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum ClientPlatform {
  /*
   */
  ANDROID("android"),
  IOS("ios"),
  WEB("web"),
  WINDOWS("windows"),
  MACOS("macos"),
  ;

  private final String value;

  ClientPlatform(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static ClientPlatform fromValue(String v) {
    for (ClientPlatform e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown ClientPlatform: " + v);
  }
}
