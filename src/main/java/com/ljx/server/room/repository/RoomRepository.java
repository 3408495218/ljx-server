package com.ljx.server.room.repository;

import com.ljx.server.room.entity.Room;
import com.ljx.server.room.entity.RoomStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RoomRepository extends JpaRepository<Room, Long>, JpaSpecificationExecutor<Room> {

    Optional<Room> findByIdAndDeletedAtIsNull(Long id);

    /** 「我的游戏」：账号名下未删除的房间，新建优先 */
    List<Room> findByOwnerIdAndDeletedAtIsNullOrderByIdDesc(Long ownerId);

    /** 心跳超时的在线房间：批量置离线前先取出，用于逐个广播下线事件 */
    @Query("select r from Room r where r.status = :online and r.deletedAt is null "
            + "and (r.lastHeartbeatAt is null or r.lastHeartbeatAt < :threshold)")
    List<Room> findStaleOnline(@Param("online") RoomStatus online,
                               @Param("threshold") LocalDateTime threshold);

    @Modifying
    @Query("update Room r set r.status = :offline, r.updatedAt = :now "
            + "where r.status = :online and r.deletedAt is null "
            + "and (r.lastHeartbeatAt is null or r.lastHeartbeatAt < :threshold)")
    int markStaleOffline(@Param("online") RoomStatus online,
                         @Param("offline") RoomStatus offline,
                         @Param("threshold") LocalDateTime threshold,
                         @Param("now") LocalDateTime now);
}