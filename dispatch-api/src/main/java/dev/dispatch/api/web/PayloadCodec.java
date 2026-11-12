package dev.dispatch.api.web;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.StringNode;

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

    /**
     * Reads what is already stored. The application's mapper carries the request size limit, and a
     * row written before that limit existed may exceed it; it must still come back as the JSON it
     * is. Jackson's own defaults apply here, as they did before the limit.
     */
    private final ObjectMapper storedReader = JsonMapper.builderWithJackson2Defaults().build();

    public PayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** JSON from a request body to the string that gets stored. Null becomes {@code {}}. */
    public String toStoredPayload(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return "{}";
        }
        try {
            return escapeUnpairedSurrogates(objectMapper.writeValueAsString(payload));
        } catch (JacksonException e) {
            // Jackson round-tripping a JsonNode it just parsed should not fail.
            throw new IllegalArgumentException("Payload could not be serialized", e);
        }
    }

    /**
     * An unpaired surrogate has no UTF-8 encoding, so the PostgreSQL driver would store it as
     * {@code ?}. In Jackson's output a non-ASCII char can only sit inside a string literal, where
     * the escape denotes the same JSON value.
     */
    private static String escapeUnpairedSurrogates(String json) {
        StringBuilder out = null;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isHighSurrogate(c)
                    && i + 1 < json.length()
                    && Character.isLowSurrogate(json.charAt(i + 1))) {
                if (out != null) {
                    out.append(c).append(json.charAt(i + 1));
                }
                i++;
            } else if (Character.isSurrogate(c)) {
                if (out == null) {
                    out = new StringBuilder(json.length() + 6).append(json, 0, i);
                }
                out.append(String.format("\\u%04X", (int) c));
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? json : out.toString();
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
            return storedReader.readTree(payload);
        } catch (JacksonException e) {
            return StringNode.valueOf(payload);
        }
    }
}
