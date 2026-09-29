package dev.dispatch.api.config;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.dispatch.api.config.QueueProperties.Retry;
import dev.dispatch.api.config.QueueProperties.StoreType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Request limits configuration")
class RequestLimitsTest {

    private static QueueProperties withMaxPayloadBytes(long bytes) {
        return new QueueProperties(StoreType.MEMORY, "", null, null, null, null, null, null, null,
                new Retry(null, null, null, null), false, bytes);
    }

    @Test
    @DisplayName("a limit of zero or less is a startup error, not Jackson's \"unlimited\"")
    void nonPositiveLimitIsRefused() {
        RequestLimits limits = new RequestLimits();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> limits.requestReadConstraints(withMaxPayloadBytes(0)))
                .withMessageContaining("dispatch.max-payload-bytes must be at least 1");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> limits.requestReadConstraints(withMaxPayloadBytes(-1)));
    }
}
