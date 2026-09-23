package io.openware.im.user.infra.persistence.social.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.im.user.infra.persistence.social.mapper.FriendRequestMapper;
import io.openware.im.user.infra.persistence.social.po.FriendRequestPo;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FriendRequestRepositoryAdapterTest {
  @Test
  void loadsFriendRequestWithWriteLockForAcceptanceFlow() {
    FriendRequestMapper mapper = mock(FriendRequestMapper.class);
    FriendRequestPo po = new FriendRequestPo();
    po.setId(10L);
    po.setFromUserId(1L);
    po.setToUserId(2L);
    po.setStatus("pending");
    po.setCreatedAt(LocalDateTime.now());
    po.setUpdatedAt(po.getCreatedAt());
    when(mapper.selectByIdForUpdate(10L)).thenReturn(po);

    Optional<?> result = new FriendRequestRepositoryAdapter(mapper).findById(10L);

    assertTrue(result.isPresent());
    verify(mapper).selectByIdForUpdate(10L);
  }
}
