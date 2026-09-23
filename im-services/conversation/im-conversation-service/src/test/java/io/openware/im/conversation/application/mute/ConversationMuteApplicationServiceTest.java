package io.openware.im.conversation.application.mute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.im.conversation.domain.mute.model.ConversationMute;
import io.openware.im.conversation.domain.mute.repository.ConversationMuteRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConversationMuteApplicationServiceTest {
  private final ConversationMuteRepository repository = mock(ConversationMuteRepository.class);
  private final ConversationMuteApplicationService service = new ConversationMuteApplicationService(repository);

  @Test
  void setMutedTrueCreatesRecordWhenNotExists() {
    when(repository.existsByUserIdAndConversationId(1L, "conv:group:7")).thenReturn(false);

    assertThat(service.setMuted(1L, "conv:group:7", true)).isTrue();
    verify(repository).save(any(ConversationMute.class));
  }

  @Test
  void setMutedTrueSkipsSaveWhenAlreadyExists() {
    when(repository.existsByUserIdAndConversationId(1L, "conv:group:7")).thenReturn(true);

    assertThat(service.setMuted(1L, "conv:group:7", true)).isTrue();
    verify(repository, never()).save(any());
  }

  @Test
  void setMutedFalseDeletesRecord() {
    assertThat(service.setMuted(1L, "conv:group:7", false)).isFalse();
    verify(repository).deleteByUserIdAndConversationId(1L, "conv:group:7");
  }

  @Test
  void getMutedConversationIdsMapsConversationId() {
    when(repository.findByUserId(1L)).thenReturn(List.of(
        ConversationMute.create(1L, "conv:group:7", LocalDateTime.now()),
        ConversationMute.create(1L, "secret:3", LocalDateTime.now())));

    assertThat(service.getMutedConversationIds(1L)).containsExactly("conv:group:7", "secret:3");
  }

  @Test
  void isMutedDelegatesToRepository() {
    when(repository.existsByUserIdAndConversationId(1L, "conv:group:7")).thenReturn(true);

    assertThat(service.isMuted(1L, "conv:group:7")).isTrue();
  }
}
