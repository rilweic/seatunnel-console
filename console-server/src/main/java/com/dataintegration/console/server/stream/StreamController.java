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
import java.util.Map;

/** Real-time jobs: their live state, start, stop, restart settings and deletion (docs/design.md §12 P2). */
@RestController
@RequestMapping("/api/streams")
public class StreamController {

    public record StopRequest(Boolean force) {}

    private final StreamService streams;

    public StreamController(StreamService streams) {
        this.streams = streams;
    }

    /** @param jobIds comma separated, e.g. the jobs of one list page */
    @GetMapping
    public Result<Map<String, StreamService.StreamSummary>> summaries(@RequestParam List<String> jobIds) {
        return Result.ok(streams.summaries(jobIds.stream().limit(200).toList()));
    }

    @GetMapping("/{jobId}")
    public Result<StreamView> view(@PathVariable String jobId) {
        return Result.ok(streams.view(jobId));
    }

    @PostMapping("/{jobId}/start")
    public Result<StreamView> start(@PathVariable String jobId, @RequestBody(required = false) StartRequest body) {
        return Result.ok(streams.start(jobId, body == null ? new StartRequest(null, null) : body));
    }

    @PostMapping("/{jobId}/stop")
    public Result<StreamView> stop(@PathVariable String jobId, @RequestBody(required = false) StopRequest body) {
        return Result.ok(streams.stop(jobId, body != null && Boolean.TRUE.equals(body.force())));
    }

    @PutMapping("/{jobId}/settings")
    public Result<StreamView> settings(@PathVariable String jobId, @RequestBody Settings body) {
        return Result.ok(streams.updateSettings(jobId, body));
    }

    /** @return SQL left to run by hand when the slot or publication could not be dropped */
    @DeleteMapping("/{jobId}")
    public Result<List<String>> delete(
            @PathVariable String jobId,
            @RequestParam(defaultValue = "true") boolean deleteRuns,
            @RequestParam(defaultValue = "true") boolean dropReplication) {
        return Result.ok(streams.delete(jobId, deleteRuns, dropReplication));
    }
}
