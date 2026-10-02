package com.ljx.server.social;

import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.exception.BizException;
import com.ljx.server.commerce.ScoreEventType;
import com.ljx.server.commerce.ScoreService;
import com.ljx.server.room.entity.Room;
import com.ljx.server.room.repository.RoomRepository;
import com.ljx.server.social.dto.SocialDtos.FavoriteStateDto;
import com.ljx.server.social.entity.RoomFavorite;
import com.ljx.server.social.entity.RoomFavoriteId;
import com.ljx.server.social.repository.RoomFavoriteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 收藏：增删均幂等，重复调用不报错也不重复计数 */
@Service
public class FavoriteService {

    private final RoomRepository roomRepository;
    private final RoomFavoriteRepository favoriteRepository;
    private final ScoreService scoreService;

    public FavoriteService(RoomRepository roomRepository,
                           RoomFavoriteRepository favoriteRepository,
                           ScoreService scoreService) {
        this.roomRepository = roomRepository;
        this.favoriteRepository = favoriteRepository;
        this.scoreService = scoreService;
    }

    @Transactional
    public FavoriteStateDto add(Long accountId, Long roomId) {
        Room room = requireRoom(roomId);
        if (favoriteRepository.findById(new RoomFavoriteId(roomId, accountId)).isEmpty()) {
            favoriteRepository.save(new RoomFavorite(roomId, accountId));
            // 自己收藏自己的房间不计分，避免刷分
            if (!room.getOwnerId().equals(accountId)) {
                scoreService.award(room.getOwnerId(), ScoreEventType.FAVORITE_RECEIVED);
            }
        }
        return new FavoriteStateDto(true, favoriteRepository.countByRoomId(roomId));
    }

    @Transactional
    public FavoriteStateDto remove(Long accountId, Long roomId) {
        requireRoom(roomId);
        favoriteRepository.deleteFavorite(roomId, accountId);
        return new FavoriteStateDto(false, favoriteRepository.countByRoomId(roomId));
    }

    public boolean isFavorited(Long accountId, Long roomId) {
        return favoriteRepository.existsByRoomIdAndAccountId(roomId, accountId);
    }

    private Room requireRoom(Long roomId) {
        return roomRepository.findByIdAndDeletedAtIsNull(roomId)
                .orElseThrow(() -> new BizException(ErrorCode.ROOM_NOT_FOUND));
    }
}