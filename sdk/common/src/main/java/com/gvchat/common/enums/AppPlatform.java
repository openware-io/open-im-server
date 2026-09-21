package com.gvchat.common.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;

/** 应用发布目标平台（版本更新场景）*/
@JsonFormat(shape = JsonFormat.Shape.STRING)
public enum AppPlatform {
  /*
   * ANDROID/IOS/WINDOWS/MACOS/LINUX/WEB 各端平台。
   */
  ANDROID("android"),
  IOS("ios"),
  WINDOWS("windows"),
  MACOS("macos"),
  LINUX("linux"),
  WEB("web"),
  ;

  private final String value;

  AppPlatform(String value) {
    this.value = value;
  }

  /** 返回 JSON/数据库使用的字符串值*/
  @JsonValue
  public String getValue() {
    return value;
  }

  /** 从字符串值解析枚举常量*/
  @JsonCreator
  public static AppPlatform fromValue(String v) {
    for (AppPlatform e : values()) {
      if (e.value.equals(v)) return e;
    }
    throw new IllegalArgumentException("Unknown AppPlatform: " + v);
  }
}
