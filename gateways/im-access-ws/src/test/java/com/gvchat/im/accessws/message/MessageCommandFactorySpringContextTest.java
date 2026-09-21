package com.gvchat.im.accessws.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gvchat.common.util.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class MessageCommandFactorySpringContextTest {
  @Test
  void createsMessageCommandFactoryFromRegisteredDependencies() {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.registerBean(ObjectMapper.class);
      context.registerBean(SnowflakeIdGenerator.class);
      context.registerBean(MessageCommandFactory.class);
      context.refresh();

      assertThat(context.getBean(MessageCommandFactory.class)).isNotNull();
    }
  }
}
