package dev.dispatch.api.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;

/**
 * Converts job payloads between the JSON the API speaks and the opaque string the engine stores.
 *
 * <p>The engine deliberately treats payloads as bytes it never looks inside, which keeps
 * {@code dispatch-core} free of a JSON dependency and lets handlers pick their own format. The API
 * speaks JSON and translates at the boundary, which keeps this class small instead of spreading
 * the concern across the controllers.
 */
@Component
public class PayloadCodec {

    private final ObjectMapper objectMapper;

    public PayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** JSON from a request body to the string that gets stored. Null becomes {@code {}}. */
    public String toStoredPayload(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Jackson round-tripping a JsonNode it just parsed should not fail.
            throw new IllegalArgumentException("Payload could not be serialized", e);
        }
    }

    /**
     * Stored string back to JSON for a response.
     *
     * <p>Anything that will not parse is returned as a JSON string rather than throwing. A payload
     * written by an older version of the app, or by hand, must not be able to make
     * {@code GET /jobs/{id}} fail. Being unable to read a job is far worse than seeing its
     * payload quoted.
     */
    public JsonNode fromStoredPayload(String payload) {
        if (payload == null) {
            return NullNode.getInstance();
        }
        try {
            return objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            return TextNode.valueOf(payload);
        }
    }
}
