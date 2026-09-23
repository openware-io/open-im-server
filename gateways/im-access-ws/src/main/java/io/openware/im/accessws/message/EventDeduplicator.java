package io.openware.im.accessws.message;

public interface EventDeduplicator {
  boolean firstDelivery(String eventId);

  void release(String eventId);
}
