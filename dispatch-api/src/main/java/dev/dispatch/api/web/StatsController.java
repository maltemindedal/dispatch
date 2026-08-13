package dev.dispatch.api.web;

import dev.dispatch.api.web.dto.StatsResponse;
import dev.dispatch.core.engine.JobQueue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Queue depth per state, plus what this instance has processed. */
@RestController
public class StatsController {

    private final JobQueue queue;

    public StatsController(JobQueue queue) {
        this.queue = queue;
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        return StatsResponse.from(queue.stats());
    }
}
