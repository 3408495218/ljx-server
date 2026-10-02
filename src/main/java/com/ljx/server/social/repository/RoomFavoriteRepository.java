package com.ljx.server.social.repository;

import com.ljx.server.social.entity.RoomFavorite;
import com.ljx.server.social.entity.RoomFavoriteId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RoomFavoriteRepository extends JpaRepository<RoomFavorite, RoomFavoriteId> {

    @Query("select f.roomId from RoomFavorite f where f.accountId = :accountId")
    List<Long> findRoomIdsByAccountId(@Param("accountId") Long accountId);

    long countByRoomId(Long roomId);

    boolean existsByRoomIdAndAccountId(Long roomId, Long accountId);

    @Modifying
    @Query("delete from RoomFavorite f where f.roomId = :roomId and f.accountId = :accountId")
    int deleteFavorite(@Param("roomId") Long roomId, @Param("accountId") Long accountId);
}