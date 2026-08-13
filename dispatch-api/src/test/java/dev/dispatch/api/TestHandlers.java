package dev.dispatch.api;

import dev.dispatch.api.config.JobHandlerRegistration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Deterministic handlers for the API tests.
 *
 * <p>The bundled demo handlers fail at random on purpose, which is exactly wrong for asserting on
 * status codes, so tests switch them off with {@code dispatch.demo-handlers=false} and use these.
 */
@TestConfiguration
public class TestHandlers {

    public static final String OK = "test-ok";
    public static final String FAIL = "test-fail";
    public static final String BLOCK = "test-block";

    private final AtomicInteger okExecutions = new AtomicInteger();
    private final AtomicInteger failExecutions = new AtomicInteger();

    /** Released by the test to let blocked handlers finish. */
    private final CountDownLatch release = new CountDownLatch(1);

    @Bean
    JobHandlerRegistration okHandler() {
        return new JobHandlerRegistration(OK, context -> okExecutions.incrementAndGet());
    }

    @Bean
    JobHandlerRegistration failHandler() {
        return new JobHandlerRegistration(FAIL, context -> {
            failExecutions.incrementAndGet();
            throw new IllegalStateException("deliberate test failure");
        });
    }

    /** Blocks until {@link #release()}, so a test can observe a job sitting in RUNNING. */
    @Bean
    JobHandlerRegistration blockHandler() {
        return new JobHandlerRegistration(BLOCK,
                context -> release.await(30, TimeUnit.SECONDS));
    }

    public int okExecutions() {
        return okExecutions.get();
    }

    public int failExecutions() {
        return failExecutions.get();
    }

    public void release() {
        release.countDown();
    }
}
