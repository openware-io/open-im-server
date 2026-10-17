package io.openware.common.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.common.payment.domain.channel.model.ChannelConfig;
import io.openware.common.payment.domain.channel.repository.ChannelConfigRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 批量渠道开关的编排已从控制器下沉到应用服务（standards/10 §应用服务规则）：
 * 校验在任何写入之前完成，整批共用同一个事务。
 */
class ChannelApplicationServiceTest {
  private final ChannelConfigRepository repository = mock(ChannelConfigRepository.class);
  private final ChannelApplicationService service = new ChannelApplicationService(repository);

  @Test
  void batchRejectsMissingStoresOrConfig() {
    IllegalArgumentException missingStores = assertThrows(IllegalArgumentException.class,
        () -> service.setEnabledBatch(1L, null, null, List.of(), toggles("ALIPAY"), "idem"));
    assertEquals("PAYMENT_CHANNEL_BATCH_INVALID", missingStores.getMessage());

    IllegalArgumentException missingConfig = assertThrows(IllegalArgumentException.class,
        () -> service.setEnabledBatch(1L, null, null, List.of(1L), List.of(), "idem"));
    assertEquals("PAYMENT_CHANNEL_BATCH_INVALID", missingConfig.getMessage());
  }

  @Test
  void batchRejectsInvalidOrDuplicateStoresBeforeWriting() {
    IllegalArgumentException duplicate = assertThrows(IllegalArgumentException.class,
        () -> service.setEnabledBatch(1L, null, null, List.of(1L, 1L), toggles("ALIPAY"), "idem"));
    assertEquals("STORE_SCOPE_FORBIDDEN", duplicate.getMessage());

    IllegalArgumentException nonPositive = assertThrows(IllegalArgumentException.class,
        () -> service.setEnabledBatch(1L, null, null, List.of(0L), toggles("ALIPAY"), "idem"));
    assertEquals("STORE_SCOPE_FORBIDDEN", nonPositive.getMessage());
  }

  @Test
  void batchRejectsChannelThatIsNotOnline() {
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> service.setEnabledBatch(1L, null, null, List.of(1L), toggles("CASH"), "idem"));

    assertEquals("CHANNEL_NOT_ONLINE", failure.getMessage());
  }

  @Test
  void batchWritesEveryStoreAndSuffixesTheIdempotencyKey() {
    when(repository.findByScope(any(), any(), any(), any())).thenReturn(Optional.empty());
    when(repository.findByIdempotencyKey(any(), any(), any())).thenReturn(Optional.empty());
    when(repository.insert(any(ChannelConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

    List<ChannelConfigDto> result = service.setEnabledBatch(1L, "KTV", "merchant-1", List.of(1L, 2L),
        toggles("ALIPAY"), "idem");

    assertEquals(2, result.size());
    ArgumentCaptor<ChannelConfig> captor = ArgumentCaptor.forClass(ChannelConfig.class);
    verify(repository, times(2)).insert(captor.capture());
    assertEquals(List.of("idem-1-ALIPAY", "idem-2-ALIPAY"),
        captor.getAllValues().stream().map(ChannelConfig::idempotencyKey).toList());
    assertTrue(captor.getAllValues().stream().allMatch(row -> "KTV".equals(row.businessType())));
  }

  private static List<ChannelApplicationService.ChannelToggleCommand> toggles(String channel) {
    return List.of(new ChannelApplicationService.ChannelToggleCommand(channel, true, null));
  }
}
