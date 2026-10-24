package com.dataintegration.console.server.dashboard;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.dataintegration.console.common.ConsoleException;
import com.dataintegration.console.engine.EngineClient;
import com.dataintegration.console.server.auth.CurrentUser;
import com.dataintegration.console.server.job.ScheduleService;
import com.dataintegration.console.server.persistence.entity.JobDefinition;
import com.dataintegration.console.server.persistence.entity.JobInstance;
import com.dataintegration.console.server.persistence.entity.JobSchedule;
import com.dataintegration.console.server.persistence.mapper.JobDefinitionMapper;
import com.dataintegration.console.server.persistence.mapper.JobInstanceMapper;
import com.dataintegration.console.server.persistence.mapper.JobScheduleMapper;
import com.dataintegration.console.server.run.InstanceService;
import com.dataintegration.console.server.run.InstanceService.InstanceView;
import com.dataintegration.console.server.run.RunStatus;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The home page (docs/design.md §10.5): how all jobs, or the current user's, are running today and
 * lately, what runs now, what failed, what comes next, and the cluster at a glance.
 */
@Service
public class DashboardService {

    static final int TREND_DAYS = 14;
    static final int LIST_SIZE = 8;

    /** Runs created on one day, by outcome; active runs count as running. */
    public record Day(LocalDate date, long success, long failed, long canceled, long running, long writeRows) {}

    public record JobStat(String jobId, String jobName, long runs, long writeRows, Long avgDurationMillis) {}

    public record Upcoming(String jobId, String jobName, LocalDateTime time, String cron) {}

    /**
     * @param successRate7d success ÷ (success + failed) over the last 7 days, null without such runs
     * @param cluster the engine's overview, empty when the cluster cannot be reached
     */
    public record Dashboard(
            long jobs,
            long scheduledJobs,
            long runningRuns,
            long pendingRuns,
            Day today,
            Double successRate7d,
            long writeRows7d,
            List<Day> trend,
            List<InstanceView> active,
            List<InstanceView> recentFailures,
            List<Upcoming> upcoming,
            List<JobStat> topByRows,
            List<JobStat> slowest,
            Map<String, String> cluster) {}

    private final JobDefinitionMapper jobMapper;
    private final JobInstanceMapper instanceMapper;
    private final JobScheduleMapper scheduleMapper;
    private final InstanceService instanceService;
    private final EngineClient engineClient;

    public DashboardService(
            JobDefinitionMapper jobMapper,
            JobInstanceMapper instanceMapper,
            JobScheduleMapper scheduleMapper,
            InstanceService instanceService,
            EngineClient engineClient) {
        this.jobMapper = jobMapper;
        this.instanceMapper = instanceMapper;
        this.scheduleMapper = scheduleMapper;
        this.instanceService = instanceService;
        this.engineClient = engineClient;
    }

    /** @param mine only jobs the current user created, and their runs */
    public Dashboard dashboard(boolean mine) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        String me = CurrentUser.idOrNull();

        List<JobDefinition> jobs = jobMapper.selectList(new LambdaQueryWrapper<JobDefinition>()
                .select(JobDefinition::getId, JobDefinition::getName, JobDefinition::getCreatedBy)
                .eq(mine, JobDefinition::getCreatedBy, me));
        Map<String, String> jobNames = jobs.stream().collect(Collectors.toMap(JobDefinition::getId, JobDefinition::getName));
        String scope = mine ? "select id from job_definition where created_by = " + quote(me) : null;

        List<JobSchedule> schedules = jobNames.isEmpty()
                ? List.of()
                : scheduleMapper.selectList(new LambdaQueryWrapper<JobSchedule>()
                        .eq(JobSchedule::getEnabled, true)
                        .in(JobSchedule::getJobDefinitionId, jobNames.keySet()));

        List<Day> trend = trend(today, scope);
        Day todayStats = trend.get(trend.size() - 1);

        List<JobInstance> active = instanceMapper.selectList(scoped(new LambdaQueryWrapper<JobInstance>(), scope)
                .select(JobInstance.class, f -> !f.getColumn().equals("config_masked"))
                .in(JobInstance::getStatus, RunStatus.PENDING.name(), RunStatus.RUNNING.name())
                .orderByDesc(JobInstance::getCreatedAt));
        List<JobInstance> failures = instanceMapper.selectList(scoped(new LambdaQueryWrapper<JobInstance>(), scope)
                .select(JobInstance.class, f -> !f.getColumn().equals("config_masked"))
                .eq(JobInstance::getStatus, RunStatus.FAILED.name())
                .orderByDesc(JobInstance::getFinishedAt)
                .last("limit " + LIST_SIZE));

        List<Day> week = trend.subList(trend.size() - 7, trend.size());
        long success7 = week.stream().mapToLong(Day::success).sum();
        long failed7 = week.stream().mapToLong(Day::failed).sum();

        return new Dashboard(
                jobs.size(),
                schedules.size(),
                active.stream().filter(i -> RunStatus.RUNNING.name().equals(i.getStatus())).count(),
                active.stream().filter(i -> RunStatus.PENDING.name().equals(i.getStatus())).count(),
                todayStats,
                success7 + failed7 == 0 ? null : (double) success7 / (success7 + failed7),
                week.stream().mapToLong(Day::writeRows).sum(),
                trend,
                instanceService.views(active.subList(0, Math.min(active.size(), LIST_SIZE))),
                instanceService.views(failures),
                upcoming(schedules, jobNames, now),
                topByRows(now, scope, jobNames),
                slowest(now, scope, jobNames),
                cluster());
    }

    private List<Day> trend(LocalDate today, String scope) {
        LocalDate first = today.minusDays(TREND_DAYS - 1);
        List<Map<String, Object>> rows = instanceMapper.selectMaps(scoped(new QueryWrapper<JobInstance>(), scope)
                .select("date(created_at) as d", "status", "count(*) as c", "coalesce(sum(write_rows), 0) as w")
                .ge("created_at", first.atStartOfDay())
                .groupBy("date(created_at)", "status"));
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (int i = 0; i < TREND_DAYS; i++) {
            byDay.put(first.plusDays(i), new long[5]);
        }
        for (Map<String, Object> row : rows) {
            long[] counts = byDay.get(toDate(row.get("d")));
            if (counts == null) {
                continue;
            }
            long c = ((Number) row.get("c")).longValue();
            switch (RunStatus.valueOf((String) row.get("status"))) {
                case SUCCESS -> counts[0] += c;
                case FAILED -> counts[1] += c;
                case CANCELED -> counts[2] += c;
                default -> counts[3] += c;
            }
            counts[4] += ((Number) row.get("w")).longValue();
        }
        List<Day> days = new ArrayList<>();
        byDay.forEach((d, c) -> days.add(new Day(d, c[0], c[1], c[2], c[3], c[4])));
        return days;
    }

    /** The next fire times of enabled schedules within a day, soonest first. */
    private List<Upcoming> upcoming(List<JobSchedule> schedules, Map<String, String> jobNames, LocalDateTime now) {
        LocalDateTime until = now.plusDays(1);
        List<Upcoming> upcoming = new ArrayList<>();
        for (JobSchedule schedule : schedules) {
            try {
                for (LocalDateTime time : ScheduleService.nextFireTimes(schedule.getCron(), now, LIST_SIZE)) {
                    if (time.isAfter(until)) {
                        break;
                    }
                    upcoming.add(new Upcoming(schedule.getJobDefinitionId(), jobNames.get(schedule.getJobDefinitionId()), time, schedule.getCron()));
                }
            } catch (ConsoleException e) {
                // A cron that no longer parses is shown on its job's page, not here.
            }
        }
        upcoming.sort(Comparator.comparing(Upcoming::time));
        return upcoming.subList(0, Math.min(upcoming.size(), LIST_SIZE));
    }

    private List<JobStat> topByRows(LocalDateTime now, String scope, Map<String, String> jobNames) {
        return jobStats(scoped(new QueryWrapper<JobInstance>(), scope)
                        .select("job_definition_id as j", "count(*) as c", "coalesce(sum(write_rows), 0) as w",
                                "avg(timestampdiff(microsecond, started_at, finished_at)) / 1000 as a")
                        .ge("created_at", now.minusDays(7))
                        .in("status", RunStatus.SUCCESS.name(), RunStatus.FAILED.name())
                        .groupBy("job_definition_id")
                        .orderByDesc("w")
                        .last("limit 5"),
                jobNames);
    }

    private List<JobStat> slowest(LocalDateTime now, String scope, Map<String, String> jobNames) {
        return jobStats(scoped(new QueryWrapper<JobInstance>(), scope)
                        .select("job_definition_id as j", "count(*) as c", "coalesce(sum(write_rows), 0) as w",
                                "avg(timestampdiff(microsecond, started_at, finished_at)) / 1000 as a")
                        .ge("created_at", now.minusDays(7))
                        .eq("status", RunStatus.SUCCESS.name())
                        .isNotNull("started_at")
                        .groupBy("job_definition_id")
                        .orderByDesc("a")
                        .last("limit 5"),
                jobNames);
    }

    private List<JobStat> jobStats(QueryWrapper<JobInstance> query, Map<String, String> jobNames) {
        return instanceMapper.selectMaps(query).stream()
                .filter(row -> jobNames.containsKey((String) row.get("j")))
                .map(row -> new JobStat(
                        (String) row.get("j"),
                        jobNames.get((String) row.get("j")),
                        ((Number) row.get("c")).longValue(),
                        ((Number) row.get("w")).longValue(),
                        row.get("a") == null ? null : ((Number) row.get("a")).longValue()))
                .toList();
    }

    private Map<String, String> cluster() {
        try {
            Map<String, String> overview = engineClient.overview();
            Map<String, String> picked = new HashMap<>();
            for (String key : Set.of("workers", "runningJobs", "pendingJobs", "totalSlot", "unassignedSlot", "projectVersion")) {
                if (overview.get(key) != null) {
                    picked.put(key, overview.get(key));
                }
            }
            return picked;
        } catch (ConsoleException e) {
            return Map.of();
        }
    }

    /** Limits a query on job_instance to the jobs in scope; null scope means all jobs. */
    private static QueryWrapper<JobInstance> scoped(QueryWrapper<JobInstance> wrapper, String scope) {
        if (scope != null) {
            wrapper.inSql("job_definition_id", scope);
        }
        return wrapper;
    }

    private static LambdaQueryWrapper<JobInstance> scoped(LambdaQueryWrapper<JobInstance> wrapper, String scope) {
        if (scope != null) {
            wrapper.inSql(JobInstance::getJobDefinitionId, scope);
        }
        return wrapper;
    }

    private static LocalDate toDate(Object value) {
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        return value instanceof LocalDateTime time ? time.toLocalDate() : LocalDate.parse(String.valueOf(value));
    }

    /** User ids are UUIDs, never user input, but quoted safely anyway for the inSql fragment. */
    private static String quote(String id) {
        return id == null ? "null" : "'" + id.replace("'", "''") + "'";
    }
}
