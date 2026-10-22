package com.zzl.web.controller.system;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.zzl.common.constant.CacheConstants;
import com.zzl.common.constant.ScheduleConstants;
import com.zzl.common.core.controller.BaseController;
import com.zzl.common.core.domain.AjaxResult;
import com.zzl.common.core.domain.entity.SysUser;
import com.zzl.common.core.redis.RedisCache;
import com.zzl.quartz.domain.SysJob;
import com.zzl.quartz.service.ISysJobService;
import com.zzl.system.domain.SysOperLog;
import com.zzl.system.service.ISysNoticeReadService;
import com.zzl.system.service.ISysOperLogService;
import com.zzl.system.service.ISysUserService;

/**
 * 首页工作台数据聚合接口。
 *
 * <p>这里复用现有系统服务，集中返回首页需要的实时数据，避免前端拼接多个管理接口。</p>
 *
 * @author zzl
 */
@RestController
@RequestMapping("/system/dashboard")
public class SysDashboardController extends BaseController
{
    private static final DateTimeFormatter ACTIVITY_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final ScheduledExecutorService DASHBOARD_STREAM_EXECUTOR = Executors.newScheduledThreadPool(2, runnable ->
    {
        Thread thread = new Thread(runnable, "dashboard-sse");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    private ISysUserService userService;

    @Autowired
    private ISysNoticeReadService noticeReadService;

    @Autowired
    private ISysOperLogService operLogService;

    @Autowired
    private ISysJobService jobService;

    @Autowired
    private RedisCache redisCache;

    /**
     * 获取首页工作台聚合数据。
     *
     * @return 指标、服务状态、待办、最近活动和更新时间
     */
    @GetMapping("/overview")
    public AjaxResult overview()
    {
        return buildOverview(getUserId());
    }

    /**
     * 打开首页实时数据流。首次立即推送，之后每30秒推送一次。
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream()
    {
        Long userId = getUserId();
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
        Runnable cleanup = () ->
        {
            if (active.compareAndSet(true, false))
            {
                Optional.ofNullable(futureRef.getAndSet(null)).ifPresent(future -> future.cancel(false));
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());

        Runnable push = () ->
        {
            if (!active.get())
            {
                return;
            }
            try
            {
                AjaxResult result = buildOverview(userId);
                emitter.send(SseEmitter.event().name("dashboard").data(result.get(AjaxResult.DATA_TAG)));
            }
            catch (IOException | RuntimeException error)
            {
                cleanup.run();
                emitter.completeWithError(error);
            }
        };
        ScheduledFuture<?> future = DASHBOARD_STREAM_EXECUTOR.scheduleAtFixedRate(push, 0, 30, TimeUnit.SECONDS);
        futureRef.set(future);
        return emitter;
    }

    private AjaxResult buildOverview(Long userId)
    {
        Date now = new Date();
        List<SysUser> users = userService.selectUserList(new SysUser());
        List<SysJob> jobs = jobService.selectJobList(new SysJob());
        int unreadNoticeCount = noticeReadService.selectUnreadCount(userId);
        List<SysOperLog> recentLogs = selectRecentOperLogs(now);

        RedisSnapshot redis = readRedisSnapshot();
        long pausedJobCount = jobs.stream().filter(job -> ScheduleConstants.Status.PAUSE.getValue().equals(job.getStatus())).count();
        long failedOperationCount = recentLogs.stream().filter(log -> Objects.equals(log.getStatus(), 1)).count();
        int todoCount = (int) Math.min(Integer.MAX_VALUE,
                (long) unreadNoticeCount + pausedJobCount + failedOperationCount);

        List<Map<String, Object>> serviceHealth = buildServiceHealth(redis, jobs);
        List<Map<String, Object>> todo = buildTodo(unreadNoticeCount, pausedJobCount, failedOperationCount);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metrics", buildMetrics(redis.onlineCount(), users.size(), todoCount, unreadNoticeCount));
        data.put("serviceHealth", serviceHealth);
        data.put("services", serviceHealth);
        data.put("activities", buildActivities(recentLogs));
        data.put("todo", todo);
        data.put("tasks", todo);
        data.put("todoCount", todoCount);
        data.put("updatedAt", Instant.now().toString());
        data.put("window", "last24h");
        return AjaxResult.success(data);
    }

    /**
     * 只读取最近24小时的操作日志，首页只展示最近八条。
     */
    private List<SysOperLog> selectRecentOperLogs(Date now)
    {
        SysOperLog query = new SysOperLog();
        Date beginTime = Date.from(now.toInstant().minus(24, ChronoUnit.HOURS));
        query.getParams().put("beginTime", beginTime);
        query.getParams().put("endTime", now);
        return operLogService.selectOperLogList(query).stream().limit(8).collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildMetrics(int onlineUserCount, int totalUserCount, int todoCount,
            int unreadNoticeCount)
    {
        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metric("onlineUsers", "在线用户", onlineUserCount, "当前登录会话", "UserFilled", "mint"));
        metrics.add(metric("totalUsers", "用户总数", totalUserCount, "已注册账号", "User", "blue"));
        metrics.add(metric("todo", "待办事项", todoCount, "需要处理的实际事项", "Tickets", "banana"));
        metrics.add(metric("unreadNotices", "未读通知", unreadNoticeCount, "当前账号未读公告", "Bell", "coral"));
        return metrics;
    }

    private Map<String, Object> metric(String key, String label, int value, String trend, String icon, String tone)
    {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("key", key);
        metric.put("label", label);
        metric.put("value", value);
        metric.put("trend", trend);
        metric.put("icon", icon);
        metric.put("tone", tone);
        return metric;
    }

    private List<Map<String, Object>> buildServiceHealth(RedisSnapshot redis, List<SysJob> jobs)
    {
        long pausedJobCount = jobs.stream().filter(job -> ScheduleConstants.Status.PAUSE.getValue().equals(job.getStatus())).count();
        int schedulerPercent = jobs.isEmpty() ? 100 : (int) Math.round((jobs.size() - pausedJobCount) * 100D / jobs.size());
        String schedulerStatus = pausedJobCount == 0 ? "UP" : "DEGRADED";

        List<Map<String, Object>> health = new ArrayList<>();
        health.add(service("api", "API 服务", "UP", 100, "请求聚合接口成功", "mint"));
        health.add(service("redis", "Redis 缓存", redis.available() ? "UP" : "DOWN", redis.available() ? 100 : 0,
                redis.available() ? "连接正常" : "连接不可用", "blue"));
        health.add(service("database", "数据库", "UP", 100, "数据查询正常", "banana"));
        health.add(service("scheduler", "定时任务", schedulerStatus, schedulerPercent,
                jobs.isEmpty() ? "暂无定时任务" : "正常任务 " + (jobs.size() - pausedJobCount) + " / " + jobs.size(), "coral"));
        return health;
    }

    private Map<String, Object> service(String key, String name, String status, int percent, String detail, String tone)
    {
        Map<String, Object> service = new LinkedHashMap<>();
        service.put("key", key);
        service.put("name", name);
        service.put("status", status);
        service.put("percent", percent);
        service.put("detail", detail);
        service.put("tone", tone);
        return service;
    }

    private List<Map<String, Object>> buildTodo(int unreadNoticeCount, long pausedJobCount, long failedOperationCount)
    {
        List<Map<String, Object>> todo = new ArrayList<>();
        if (unreadNoticeCount > 0)
        {
            todo.add(todoItem("unreadNotices", "查看未读通知", "有 " + unreadNoticeCount + " 条公告等待查看", "high", "/system/notice"));
        }
        if (pausedJobCount > 0)
        {
            todo.add(todoItem("pausedJobs", "检查暂停任务", "有 " + pausedJobCount + " 个定时任务处于暂停状态", "medium", "/monitor/job"));
        }
        if (failedOperationCount > 0)
        {
            todo.add(todoItem("failedOperations", "查看异常操作", "最近24小时有 " + failedOperationCount + " 条异常操作记录", "medium", "/monitor/operlog"));
        }
        return todo;
    }

    private Map<String, Object> todoItem(String key, String title, String desc, String level, String path)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", key);
        item.put("title", title);
        item.put("desc", desc);
        item.put("level", level);
        item.put("path", path);
        return item;
    }

    private List<Map<String, Object>> buildActivities(List<SysOperLog> logs)
    {
        List<Map<String, Object>> activities = new ArrayList<>();
        for (SysOperLog log : logs)
        {
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("id", log.getOperId());
            activity.put("time", formatActivityTime(log.getOperTime()));
            activity.put("title", defaultText(log.getTitle(), "系统操作"));
            activity.put("detail", buildActivityDetail(log));
            activity.put("status", Objects.equals(log.getStatus(), 0) ? "success" : "error");
            activity.put("operName", defaultText(log.getOperName(), "未知用户"));
            activities.add(activity);
        }
        return activities;
    }

    private String buildActivityDetail(SysOperLog log)
    {
        String operator = defaultText(log.getOperName(), "未知用户");
        String method = defaultText(log.getRequestMethod(), "操作");
        String url = defaultText(log.getOperUrl(), "系统功能");
        return operator + " · " + method + " " + url;
    }

    private String formatActivityTime(Date time)
    {
        if (time == null)
        {
            return "--:--";
        }
        LocalDateTime localTime = LocalDateTime.ofInstant(time.toInstant(), ZoneId.systemDefault());
        return ACTIVITY_TIME_FORMATTER.format(localTime);
    }

    private String defaultText(String value, String fallback)
    {
        return value == null || value.isBlank() ? fallback : value;
    }

    private RedisSnapshot readRedisSnapshot()
    {
        try
        {
            Collection<String> keys = redisCache.keys(CacheConstants.LOGIN_TOKEN_KEY + "*");
            return new RedisSnapshot(true, keys == null ? 0 : keys.size());
        }
        catch (RuntimeException ex)
        {
            return new RedisSnapshot(false, 0);
        }
    }

    private record RedisSnapshot(boolean available, int onlineCount)
    {
    }
}
