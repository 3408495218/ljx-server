package com.ljx.server.commerce;

import com.ljx.server.commerce.entity.RoomTopCard;
import com.ljx.server.commerce.repository.RoomTopCardRepository;
import com.ljx.server.room.repository.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 置顶卡到期清理。
 * <p>
 * 为什么必须有：大厅排序用 {@code room.top_expires_at DESC}，而空值排在最后。
 * 已过期的置顶记录若不清理，它就是"非空且较早"——仍会排在没有置顶的房间**之前**，
 * 变成"过期了还占着前排"。所以这里定期把过期记录删掉，并把房间上的冗余列置回 null。
 */
@Component
public class TopCardScheduler {

    private static final Logger log = LoggerFactory.getLogger(TopCardScheduler.class);

    private final RoomTopCardRepository roomTopCardRepository;
    private final RoomRepository roomRepository;

    public TopCardScheduler(RoomTopCardRepository roomTopCardRepository, RoomRepository roomRepository) {
        this.roomTopCardRepository = roomTopCardRepository;
        this.roomRepository = roomRepository;
    }

    /** 每 5 分钟一次；错开启动初期，避免与其它初始化任务抢连接 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 45_000)
    @Transactional
    public void clearExpired() {
        LocalDateTime now = LocalDateTime.now();
        List<RoomTopCard> expired = roomTopCardRepository.findByExpiresAtBefore(now);
        if (expired.isEmpty()) {
            return;
        }
        for (RoomTopCard card : expired) {
            roomRepository.findByIdAndDeletedAtIsNull(card.getRoomId())
                    .ifPresent(room -> room.setTopExpiresAt(null));
        }
        roomTopCardRepository.deleteAll(expired);
        log.info("置顶卡清理：{} 个房间的置顶已到期", expired.size());
    }
}
