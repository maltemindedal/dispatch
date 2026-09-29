package dev.dispatch.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * The H2 console is off in the dev profile and comes on only when {@code DISPATCH_H2_CONSOLE=true}
 * is set; {@link RestContractTest} covers the off half against the shipped profile.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "dispatch.store=memory",
                "dispatch.poll-interval=1h",
                "dispatch.maintenance-interval=1h",
                "DISPATCH_H2_CONSOLE=true"
        })
@ActiveProfiles("dev")
@DisplayName("H2 console opt-in")
class H2ConsoleOptInTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("DISPATCH_H2_CONSOLE=true serves the console at /h2-console")
    void consoleIsServedWhenAskedFor() throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/h2-console/"))
                        .timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).containsIgnoringCase("H2 Console");
    }
}
