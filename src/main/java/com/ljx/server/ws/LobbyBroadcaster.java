package com.ljx.server.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 大厅广播出口：只在事务提交后推送，避免把回滚掉的状态变化播出去。
 * 广播失败不影响业务事务（推送是尽力而为）。
 */
@Component
public class LobbyBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LobbyBroadcaster.class);
    private static final String TOPIC = "/topic/lobby";

    private final SimpMessagingTemplate messaging;

    public LobbyBroadcaster(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRoomChanged(LobbyEvent event) {
        try {
            messaging.convertAndSend(TOPIC, event);
        } catch (RuntimeException e) {
            log.warn("lobby broadcast failed for room {}: {}", event.roomId(), e.getMessage());
        }
    }
}