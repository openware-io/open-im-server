package io.openware.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

/** RTC 通话媒体类型*/
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum RtcMediaType {
  /*
   */
  AUDIO("audio"),
  VIDEO("video"),
  ;

  private final String value;

  RtcMediaType(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static RtcMediaType fromValue(String v) {
    for (RtcMediaType e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown RtcMediaType: " + v);
  }
}
