package com.ljx.server.social;

import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.exception.BizException;
import com.ljx.server.room.repository.RoomRepository;
import com.ljx.server.social.repository.JoinRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 足迹清理：写入在大厅 join 流程（RoomService.join），列表读取走大厅三视图
 * （{@code GET /api/rooms?view=history}），本服务只负责移除与清空。
 */
@Service
public class FootprintService {

    private final RoomRepository roomRepository;
    private final JoinRecordRepository joinRecordRepository;

    public FootprintService(RoomRepository roomRepository, JoinRecordRepository joinRecordRepository) {
        this.roomRepository = roomRepository;
        this.joinRecordRepository = joinRecordRepository;
    }

    @Transactional
    public void remove(Long accountId, Long roomId) {
        roomRepository.findByIdAndDeletedAtIsNull(roomId)
                .orElseThrow(() -> new BizException(ErrorCode.ROOM_NOT_FOUND));
        joinRecordRepository.deleteByAccountIdAndRoomId(accountId, roomId);
    }

    @Transactional
    public int clear(Long accountId) {
        return joinRecordRepository.deleteByAccountId(accountId);
    }
}