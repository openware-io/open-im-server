package io.openware.common.mail.infra.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class SmtpMailProviderTest {

    @Test
    void providerNameIsSmtp() {
        assertEquals("smtp", new SmtpMailProvider().provider());
    }

    @Test
    void placeholderConfigIsDisabled() {
        assertFalse(new SmtpMailProvider().enabled());
    }

    @Test
    void sendWithPlaceholderConfigDoesNotThrow() {
        SmtpMailProvider provider = new SmtpMailProvider();
        assertDoesNotThrow(() -> provider.send("noreply@example.com", "to@example.com", "subject", "content"));
    }
}
