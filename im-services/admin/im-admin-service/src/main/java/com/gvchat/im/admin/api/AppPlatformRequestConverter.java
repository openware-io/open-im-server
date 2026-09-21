package com.gvchat.im.admin.api;

import com.gvchat.common.enums.AppPlatform;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** Converts the lowercase platform values in the public HTTP contract to the domain enum. */
@Component
public class AppPlatformRequestConverter implements Converter<String, AppPlatform> {
  @Override
  public AppPlatform convert(String source) {
    return AppPlatform.fromValue(source);
  }
}
