package com.dataintegration.console.server.stream;

import com.dataintegration.console.common.Result;
import com.dataintegration.console.server.stream.StreamService.Settings;
import com.dataintegration.console.server.stream.StreamService.StartRequest;
import com.dataintegration.console.server.stream.StreamService.StreamView;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Real-time jobs: their live state, start, stop, restart settings and deletion (docs/design.md §12 P2). */
@RestController
@RequestMapping("/api/streams/{jobId}")
public class StreamController {

    public record StopRequest(Boolean force) {}

    private final StreamService streams;

    public StreamController(StreamService streams) {
        this.streams = streams;
    }

    @GetMapping
    public Result<StreamView> view(@PathVariable String jobId) {
        return Result.ok(streams.view(jobId));
    }

    @PostMapping("/start")
    public Result<StreamView> start(@PathVariable String jobId, @RequestBody(required = false) StartRequest body) {
        return Result.ok(streams.start(jobId, body == null ? new StartRequest(null, null) : body));
    }

    @PostMapping("/stop")
    public Result<StreamView> stop(@PathVariable String jobId, @RequestBody(required = false) StopRequest body) {
        return Result.ok(streams.stop(jobId, body != null && Boolean.TRUE.equals(body.force())));
    }

    @PutMapping("/settings")
    public Result<StreamView> settings(@PathVariable String jobId, @RequestBody Settings body) {
        return Result.ok(streams.updateSettings(jobId, body));
    }

    /** @return SQL left to run by hand when the slot or publication could not be dropped */
    @DeleteMapping
    public Result<List<String>> delete(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "true") boolean deleteRuns,
            @RequestParam(defaultValue = "true") boolean dropReplication) {
        return Result.ok(streams.delete(jobId, deleteRuns, dropReplication));
    }
}
