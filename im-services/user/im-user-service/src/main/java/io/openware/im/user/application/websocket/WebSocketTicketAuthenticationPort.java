package io.openware.im.user.application.websocket;

import java.util.OptionalLong;

/** Supplies the currently valid authentication version for a ticket subject. */
public interface WebSocketTicketAuthenticationPort {
  OptionalLong activeAuthenticationVersion(long userId);
}
