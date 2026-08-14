package dev.dispatch.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("In-memory handler registry")
class InMemoryJobHandlerRegistryTest {

    private final InMemoryJobHandlerRegistry registry = new InMemoryJobHandlerRegistry();

    @Test
    @DisplayName("registering the same type twice is refused, not silently replaced")
    void duplicateRegistrationIsRefused() {
        registry.register("send-email", context -> { });

        assertThatThrownBy(() -> registry.register("send-email", context -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("send-email");
    }

    @Test
    @DisplayName("replace is the explicit way to swap a handler")
    void replaceSwapsExplicitly() {
        JobHandler first = context -> { };
        JobHandler second = context -> { };
        registry.register("send-email", first);

        registry.replace("send-email", second);

        assertThat(registry.lookup("send-email")).containsSame(second);
    }

    @Test
    @DisplayName("require throws the same exception the engine's submit guard uses")
    void requireThrowsUnknownJobType() {
        assertThatThrownBy(() -> registry.require("nobody"))
                .isInstanceOf(UnknownJobTypeException.class);
    }
}
