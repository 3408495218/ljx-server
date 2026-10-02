package com.ljx.server.snapshot.repository;

import com.ljx.server.snapshot.entity.Snapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SnapshotRepository extends JpaRepository<Snapshot, Long> {

    List<Snapshot> findByRoomIdOrderByIdDesc(Long roomId);

    /** 快照在房间内按名称唯一：本地文件以名称为主键，云端登记同一名称视为同一条记录 */
    Optional<Snapshot> findByRoomIdAndName(Long roomId, String name);

    @Modifying
    @Query("delete from Snapshot s where s.roomId = :roomId and s.name = :name")
    int deleteByRoomIdAndName(@Param("roomId") Long roomId, @Param("name") String name);
}