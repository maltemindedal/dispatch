package dev.dispatch.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The codec's whole reason to exist is its documented promise: a payload that will not parse must
 * never break a read.
 */
@DisplayName("Payload codec")
class PayloadCodecTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PayloadCodec codec = new PayloadCodec(objectMapper);

    @Test
    @DisplayName("an unparseable stored payload comes back as a JSON string, not an exception")
    void unparseablePayloadNeverBreaksARead() {
        JsonNode node = codec.fromStoredPayload("{oops, hand-written");

        assertThat(node.isTextual()).isTrue();
        assertThat(node.asText()).isEqualTo("{oops, hand-written");
    }

    @Test
    @DisplayName("a stored JSON payload comes back as the tree it was")
    void validPayloadRoundTrips() throws Exception {
        JsonNode original = objectMapper.readTree("{\"to\":\"a@b.c\",\"n\":3}");

        String stored = codec.toStoredPayload(original);
        JsonNode back = codec.fromStoredPayload(stored);

        assertThat(back).isEqualTo(original);
    }

    @Test
    @DisplayName("a missing request payload is stored as an empty object")
    void missingPayloadBecomesEmptyObject() {
        assertThat(codec.toStoredPayload(null)).isEqualTo("{}");
        assertThat(codec.toStoredPayload(NullNode.getInstance())).isEqualTo("{}");
    }

    @Test
    @DisplayName("a null stored payload reads as JSON null")
    void nullStoredPayloadReadsAsNullNode() {
        assertThat(codec.fromStoredPayload(null)).isEqualTo(NullNode.getInstance());
    }
}
