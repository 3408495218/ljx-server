package com.ljx.server.content.repository;

import com.ljx.server.content.entity.RoomContent;
import com.ljx.server.content.entity.RoomContentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RoomContentRepository extends JpaRepository<RoomContent, RoomContentId> {

    List<RoomContent> findByRoomIdOrderByTypeAscNameAsc(Long roomId);

    @Modifying
    @Query("delete from RoomContent c where c.roomId = :roomId")
    int deleteByRoomId(@Param("roomId") Long roomId);
}