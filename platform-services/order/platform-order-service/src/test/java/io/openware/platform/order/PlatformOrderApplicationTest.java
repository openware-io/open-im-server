package io.openware.platform.order;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.openware.infrastructure.security.InternalServiceAuthenticationFilter;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

class PlatformOrderApplicationTest {

    @Test
    void importsInternalServiceAuthenticationFilter() {
        Import imported = PlatformOrderApplication.class.getAnnotation(Import.class);

        assertTrue(imported != null
                && Arrays.asList(imported.value()).contains(InternalServiceAuthenticationFilter.class));
    }
}
