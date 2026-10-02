package com.ljx.server.room;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 心跳超时下线：每 15 秒扫描一次，超过 90 秒未心跳的房间置 OFFLINE */
@Component
public class RoomHeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(RoomHeartbeatScheduler.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private final RoomService roomService;

    public RoomHeartbeatScheduler(RoomService roomService) {
        this.roomService = roomService;
    }

    @Scheduled(fixedDelay = 15_000L, initialDelay = 15_000L)
    public void scanStaleRooms() {
        int expired = roomService.expireStale(TIMEOUT);
        if (expired > 0) {
            log.info("rooms marked offline due to heartbeat timeout: {}", expired);
        }
    }
}