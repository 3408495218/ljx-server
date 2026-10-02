package com.ljx.server.common.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 进程内固定窗口计数器：不引入 Redis，房间量与登录量级下足够。
 * 窗口过期即重置计数；过期条目由定时任务回收，避免按 IP 无限增长。
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private record Window(long resetAtMillis, AtomicInteger count) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /** 计数并判断是否放行；返回 false 表示该窗口内已超限 */
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        Window current = windows.compute(key, (k, existing) -> {
            if (existing == null || now >= existing.resetAtMillis()) {
                return new Window(now + window.toMillis(), new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });
        return current.count().get() <= limit;
    }

    @Scheduled(fixedDelay = 600_000L, initialDelay = 600_000L)
    public void sweepExpired() {
        long now = System.currentTimeMillis();
        int before = windows.size();
        windows.entrySet().removeIf(entry -> now >= entry.getValue().resetAtMillis());
        int removed = before - windows.size();
        if (removed > 0) {
            log.debug("rate limit windows swept: {}", removed);
        }
    }
}