package com.ljx.server.social.repository;

import com.ljx.server.social.entity.JoinRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface JoinRecordRepository extends JpaRepository<JoinRecord, Long> {

    /** 足迹：按最近一次加入时间倒序的房间 id 列表（去重） */
    @Query("select j.roomId from JoinRecord j where j.accountId = :accountId "
            + "group by j.roomId order by max(j.joinedAt) desc")
    List<Long> findRoomIdsByAccountIdOrderByLatest(@Param("accountId") Long accountId);

    /** 移除某房间的全部足迹（同一房间可能有多条加入记录） */
    @Modifying
    @Query("delete from JoinRecord j where j.accountId = :accountId and j.roomId = :roomId")
    int deleteByAccountIdAndRoomId(@Param("accountId") Long accountId, @Param("roomId") Long roomId);

    @Modifying
    @Query("delete from JoinRecord j where j.accountId = :accountId")
    int deleteByAccountId(@Param("accountId") Long accountId);
}