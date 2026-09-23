package io.openware.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

/** WebRTC 信令动作类型*/
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum RtcAction {
  /*
   */
  CALL("call"),
  RING("ring"),
  ANSWER("answer"),
  REJECT("reject"),
  HANGUP("hangup"),
  CANDIDATE("candidate"),
  OFFER("offer"),
  ANSWER_SDP("answer_sdp"),
  BUSY("busy"),
  TIMEOUT("timeout"),
  ;

  private final String value;

  RtcAction(String value) {
    this.value = value;
  }

  @JsonValue
  public String getValue() {
    return value;
  }

  @JsonCreator
  public static RtcAction fromValue(String v) {
    for (RtcAction e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown RtcAction: " + v);
  }
}
