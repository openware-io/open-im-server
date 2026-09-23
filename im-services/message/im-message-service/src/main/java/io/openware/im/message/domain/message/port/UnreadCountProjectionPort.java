package io.openware.im.message.domain.message.port;

import java.util.OptionalLong;

/** A rebuildable acceleration projection; MySQL remains the unread-count authority. */
public interface UnreadCountProjectionPort {
  OptionalLong find(long userId);
  void replace(long userId, long count);
  void invalidate(long userId);
}
