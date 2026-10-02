package com.ljx.server.room.repository;

import com.ljx.server.room.entity.RoomMember;
import com.ljx.server.room.entity.RoomMemberId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface RoomMemberRepository extends JpaRepository<RoomMember, RoomMemberId> {

    /** 房间成员，进房顺序（房主通常最早） */
    List<RoomMember> findByIdRoomIdOrderByJoinedAtAsc(Long roomId);

    int countByIdRoomId(Long roomId);

    /** 非房主成员数：容量只卡这一部分（房主不占位） */
    @Query("select count(m) from RoomMember m where m.id.roomId = :roomId and m.id.accountId <> :ownerId")
    int countGuests(@Param("roomId") Long roomId, @Param("ownerId") Long ownerId);

    /** 房间被删除时一并清理 */
    void deleteByIdRoomId(Long roomId);

    /** 批量成员数：大厅列表一次查询取回所有房间的人数，避免 N+1 */
    @Query("select m.id.roomId, count(m) from RoomMember m where m.id.roomId in :roomIds group by m.id.roomId")
    List<Object[]> countByRoomIds(@Param("roomIds") Collection<Long> roomIds);

    /** 超时成员：90 秒未续期视为已离开 */
    List<RoomMember> findByLastSeenBefore(LocalDateTime before);
}
