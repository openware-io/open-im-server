package io.openware.im.message.infra.persistence.message.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import io.openware.im.message.infra.persistence.message.mapper.ConversationSequenceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MySqlConversationSequenceAdapterTest {
  @Test
  void shouldAllocateSequenceFromMysqlWatermark() {
    ConversationSequenceMapper mapper = Mockito.mock(ConversationSequenceMapper.class);
    when(mapper.increment("private:7:9")).thenReturn(1);
    when(mapper.findLastSequence("private:7:9")).thenReturn(42L);

    assertEquals(42L, new MySqlConversationSequenceAdapter(mapper).next("private:7:9"));
  }

  @Test
  void shouldFailClosedWhenAuthoritativeWatermarkCannotBeUpdated() {
    ConversationSequenceMapper mapper = Mockito.mock(ConversationSequenceMapper.class);
    when(mapper.increment("private:7:9")).thenReturn(0);

    assertThrows(IllegalStateException.class,
        () -> new MySqlConversationSequenceAdapter(mapper).next("private:7:9"));
  }
}
