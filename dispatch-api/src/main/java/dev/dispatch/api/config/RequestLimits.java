package dev.dispatch.api.config;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;

/**
 * Bounds what the API will parse from a request, before it is parsed.
 *
 * <p>A JSON body becomes a tree in memory, several times larger than the bytes it came from, and
 * nothing else stands between a client and the heap: the servlet container does not cap a JSON
 * post. So the limit sits in the parser, which refuses as soon as the document passes it rather
 * than after building the tree.
 *
 * <p>These constraints live on the mapper that reads request bodies. Reading a payload that is
 * already stored is deliberately not bound by them; see {@code PayloadCodec}.
 */
@Configuration(proxyBeanMethods = false)
public class RequestLimits {

    /**
     * How deeply a request document may nest. The payload sits one level down inside the request,
     * so it may nest 63 levels. Nothing legitimate is anywhere near that; the limit is what keeps a
     * hostile {@code [[[[...} from costing a stack frame per bracket.
     */
    public static final int MAX_NESTING_DEPTH = 64;

    @Bean
    JsonFactoryBuilderCustomizer requestReadConstraints(QueueProperties properties) {
        // Jackson reads a limit of zero or less as "unlimited", which is the opposite of what
        // anyone who typed 0 here meant. Refuse it at startup instead.
        if (properties.maxPayloadBytes() < 1) {
            throw new IllegalArgumentException(
                    "dispatch.max-payload-bytes must be at least 1, was " + properties.maxPayloadBytes());
        }
        return builder -> builder.streamReadConstraints(StreamReadConstraints.builder()
                .maxDocumentLength(properties.maxPayloadBytes())
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .build());
    }
}
