package dev.dispatch.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.StringNode;

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

        assertThat(node.isString()).isTrue();
        assertThat(node.asString()).isEqualTo("{oops, hand-written");
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

    @Test
    @DisplayName("a lone high surrogate is stored as its JSON escape")
    void loneHighSurrogateIsEscaped() {
        assertThat(codec.toStoredPayload(StringNode.valueOf("a\uD800b")))
                .isEqualTo("\"a\\uD800b\"");
    }

    @Test
    @DisplayName("a lone low surrogate is stored as its JSON escape")
    void loneLowSurrogateIsEscaped() {
        assertThat(codec.toStoredPayload(StringNode.valueOf("a\uDC00b")))
                .isEqualTo("\"a\\uDC00b\"");
    }

    @Test
    @DisplayName("a low surrogate followed by a high one is two unpaired surrogates, both escaped")
    void reversedPairIsEscaped() {
        assertThat(codec.toStoredPayload(StringNode.valueOf("\uDC00\uD800")))
                .isEqualTo("\"\\uDC00\\uD800\"");
    }

    @Test
    @DisplayName("a valid surrogate pair is stored as the raw character")
    void validPairIsUntouched() {
        assertThat(codec.toStoredPayload(StringNode.valueOf("\uD83D\uDE00")))
                .isEqualTo("\"\uD83D\uDE00\"");
    }

    @Test
    @DisplayName("a payload with a lone surrogate reads back as the tree it was")
    void loneSurrogatePayloadRoundTrips() throws Exception {
        JsonNode original = objectMapper.readTree("{\"s\":\"a\\uD800b\"}");

        JsonNode back = codec.fromStoredPayload(codec.toStoredPayload(original));

        assertThat(back).isEqualTo(original);
    }
}
