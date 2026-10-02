package com.ljx.server.room;

import com.ljx.server.room.entity.RoomMember;
import com.ljx.server.room.repository.RoomMemberRepository;
import com.ljx.server.room.repository.RoomRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 成员行插入器：用<b>独立的编程式短事务</b>插入，撞主键时返回 false 而不是抛异常。
 * <p>
 * 为什么必须独立事务：客户端在开发模式（React StrictMode）会把挂载的 effect 跑两遍，
 * 同一账号的 {@code PUT /presence} 会并发到达，"先查后插"于是双双查到"不存在"、双双插入
 * → 一个成功、一个主键冲突。若冲突发生在外层事务里，整个事务被标记回滚，连
 * {@code room.players} 的同步写入也会一起丢掉（表现为「成员列表有房主，人数却是 0」）。
 * <p>
 * 为什么用 {@link TransactionTemplate} 而不是 {@code @Transactional(REQUIRES_NEW)}：
 * 注解方式下即使方法内 catch 住异常，内层事务仍是 rollback-only，提交时会抛
 * {@code UnexpectedRollbackException}（本轮实测踩到）。编程式事务让异常逃出 execute
 * 触发正常回滚，外层再 catch，语义干净。
 */
@Component
public class RoomMemberRegistrar {

    private static final Logger log = LoggerFactory.getLogger(RoomMemberRegistrar.class);

    private final RoomMemberRepository memberRepository;
    private final RoomRepository roomRepository;
    private final TransactionTemplate txTemplate;

    public RoomMemberRegistrar(RoomMemberRepository memberRepository,
                               RoomRepository roomRepository,
                               PlatformTransactionManager txManager) {
        this.memberRepository = memberRepository;
        this.roomRepository = roomRepository;
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 插入与 `room.players` 的校准都在本事务内完成：调用方事务完全不碰成员行，
     * 避免"内层回滚后外层 flush 又尝试同一条 insert"的纠缠（本轮实测踩到）。
     *
     * @return true 表示本次插入了新行；false 表示该成员已存在（并发下由别的请求先插入）
     */
    public boolean tryInsert(Long roomId, Long accountId, LocalDateTime now) {
        try {
            txTemplate.executeWithoutResult(status -> {
                memberRepository.saveAndFlush(new RoomMember(roomId, accountId, now));
                int count = memberRepository.countByIdRoomId(roomId);
                roomRepository.findById(roomId).ifPresent(room -> room.setMemberCount(count));
            });
            return true;
        } catch (DataIntegrityViolationException e) {
            // 并发重复进入：另一个请求已经插入，视为"已在房间"，不是错误
            log.debug("concurrent member insert skipped: room={} account={}", roomId, accountId);
            return false;
        }
    }
}
