package dev.dispatch.api.config;

import dev.dispatch.core.handler.JobHandler;

/**
 * A handler plus the job type it serves, published as a Spring bean.
 *
 * <p>This tiny adapter is what keeps Spring out of {@code dispatch-core}: handlers stay plain
 * {@link JobHandler} lambdas, and the wiring layer supplies the routing key. Declare one of these
 * as a {@code @Bean} and {@link QueueConfiguration} registers it automatically.
 *
 * <pre>{@code
 * @Bean
 * JobHandlerRegistration reportHandler(ReportService reports) {
 *     return new JobHandlerRegistration("generate-report", ctx -> reports.generate(ctx.payload()));
 * }
 * }</pre>
 */
public record JobHandlerRegistration(String type, JobHandler handler) {
}
