package io.openware.im.conversation.application.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.im.conversation.api.channel.ChannelResult;
import io.openware.im.conversation.api.channel.CreateChannelRequest;
import io.openware.im.conversation.domain.channel.model.Channel;
import io.openware.im.conversation.domain.channel.model.ChannelSubscription;
import io.openware.im.conversation.domain.channel.repository.ChannelRepository;
import io.openware.im.conversation.domain.channel.repository.ChannelSubscriptionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChannelApplicationServiceTest {
  private ChannelRepository channelRepository;
  private ChannelSubscriptionRepository subscriptionRepository;
  private ChannelApplicationService service;

  @BeforeEach
  void setUp() {
    channelRepository = mock(ChannelRepository.class);
    subscriptionRepository = mock(ChannelSubscriptionRepository.class);
    service = new ChannelApplicationService(channelRepository, subscriptionRepository);
  }

  @Test
  void createChannelReturnsOwnerRole() {
    when(channelRepository.save(any(Channel.class))).thenAnswer(inv -> {
      Channel ch = inv.getArgument(0);
      return Channel.restore(1L, "cTEST123", ch.getOwnerId(), ch.getName(), ch.getAvatar(), ch.getAnnouncement(),
          null, "active", ch.getCreatedBy(), ch.getCreatedAt(), ch.getUpdatedBy(), ch.getUpdatedAt());
    });
    CreateChannelRequest req = new CreateChannelRequest();
    req.setName("产品公告");
    ChannelResult result = service.createChannel(7L, req);
    assertEquals("owner", result.myRole());
    assertEquals(7L, result.ownerId());
    assertEquals("产品公告", result.name());
    assertNotNull(result.code());
  }

  @Test
  void subscribeIsIdempotentWhenAlreadySubscribed() {
    when(channelRepository.findById(1L)).thenReturn(Optional.of(channel(1L, 7L)));
    when(subscriptionRepository.findByChannelIdAndUserId(1L, 8L))
        .thenReturn(Optional.of(ChannelSubscription.subscribe(1L, 8L, 8L, LocalDateTime.now())));
    when(subscriptionRepository.countByChannelId(1L)).thenReturn(1L);

    service.subscribe(8L, 1L);

    verify(subscriptionRepository, never()).save(any());
  }

  @Test
  void subscribeSavesWhenNotSubscribed() {
    when(channelRepository.findById(1L)).thenReturn(Optional.of(channel(1L, 7L)));
    when(subscriptionRepository.findByChannelIdAndUserId(1L, 8L)).thenReturn(Optional.empty());
    when(subscriptionRepository.save(any(ChannelSubscription.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(subscriptionRepository.countByChannelId(1L)).thenReturn(1L);

    service.subscribe(8L, 1L);

    verify(subscriptionRepository).save(any(ChannelSubscription.class));
  }

  @Test
  void getChannelComputesRoles() {
    when(channelRepository.findById(1L)).thenReturn(Optional.of(channel(1L, 7L)));
    when(subscriptionRepository.findByChannelIdAndUserId(1L, 7L)).thenReturn(Optional.empty());
    when(subscriptionRepository.findByChannelIdAndUserId(1L, 8L))
        .thenReturn(Optional.of(ChannelSubscription.subscribe(1L, 8L, 8L, LocalDateTime.now())));
    when(subscriptionRepository.countByChannelId(1L)).thenReturn(2L);

    ChannelResult owner = service.getChannel(7L, 1L);
    assertEquals("owner", owner.myRole());

    ChannelResult subscriber = service.getChannel(8L, 1L);
    assertEquals("subscriber", subscriber.myRole());
    assertTrue(subscriber.subscribed());

    ChannelResult stranger = service.getChannel(9L, 1L);
    assertEquals("none", stranger.myRole());
    assertFalse(stranger.subscribed());
  }

  @Test
  void listMyChannelsReturnsOnlySubscribed() {
    when(subscriptionRepository.findByUserId(8L)).thenReturn(List.of(
        ChannelSubscription.subscribe(1L, 8L, 8L, LocalDateTime.now())));
    when(channelRepository.findByIds(List.of(1L))).thenReturn(List.of(channel(1L, 7L)));
    when(subscriptionRepository.countByChannelId(1L)).thenReturn(1L);

    List<ChannelResult> result = service.listMyChannels(8L);

    assertEquals(1, result.size());
    assertEquals("subscriber", result.get(0).myRole());
  }

  private Channel channel(long id, long ownerId) {
    LocalDateTime now = LocalDateTime.now();
    return Channel.restore(id, "c" + id + "TEST", ownerId, "频道", null, null, null, "active",
        ownerId, now, ownerId, now);
  }
}
