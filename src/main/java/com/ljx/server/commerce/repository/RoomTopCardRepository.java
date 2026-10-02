package com.ljx.server.commerce.repository;

import com.ljx.server.commerce.entity.RoomTopCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface RoomTopCardRepository extends JpaRepository<RoomTopCard, Long> {

    /** 仍在有效期内的置顶房间；大厅排序与卡片角标都用它 */
    @Query("select c.roomId from RoomTopCard c where c.expiresAt > :now")
    List<Long> findActiveRoomIds(@Param("now") LocalDateTime now);

    /** 已过期的置顶记录，由 TopCardScheduler 清理 */
    List<RoomTopCard> findByExpiresAtBefore(LocalDateTime now);

    /** 生效中的置顶记录（后台"生效中的效果"用） */
    List<RoomTopCard> findByExpiresAtAfterOrderByExpiresAtAsc(LocalDateTime now);
}
