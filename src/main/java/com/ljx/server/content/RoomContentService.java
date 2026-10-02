package com.ljx.server.content;

import com.ljx.server.content.dto.ContentDtos.ReportRoomContentRequest;
import com.ljx.server.content.dto.ContentDtos.RoomContentDto;
import com.ljx.server.content.entity.ContentType;
import com.ljx.server.content.entity.RoomContent;
import com.ljx.server.content.repository.RoomContentRepository;
import com.ljx.server.room.RoomService;
import com.ljx.server.room.entity.Room;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 房间内容（插件 / Mod）的「上报镜像」。
 * <p>
 * 真实来源是房主本机服务端的 {@code plugins/} 与 {@code mods/} 目录，平台不做分发；
 * 房主扫描后整表替换上报，玩家侧只读这份镜像。
 */
@Service
public class RoomContentService {

    private final RoomService roomService;
    private final RoomContentRepository roomContentRepository;

    public RoomContentService(RoomService roomService, RoomContentRepository roomContentRepository) {
        this.roomService = roomService;
        this.roomContentRepository = roomContentRepository;
    }

    /**
     * 对玩家展示的清单：登录即可读，不校验归属。
     * 房主关闭了某一类的可见性时，该类明细对**非房主**直接不返回——
     * 前端隐藏只是展示层，接口层同步裁剪才能防止绕过前端直接调接口拿到文件名。
     * 房主自己始终拿到完整清单（上报后回读、本地同步都依赖这一点）。
     */
    @Transactional(readOnly = true)
    public List<RoomContentDto> list(Long accountId, Long roomId) {
        Room room = roomService.findRoom(roomId);
        boolean owner = room.getOwnerId().equals(accountId);
        return roomContentRepository.findByRoomIdOrderByTypeAscNameAsc(roomId).stream()
                .filter(content -> owner || visibleTo(room, content.getType()))
                .map(RoomContentService::toDto)
                .toList();
    }

    private static boolean visibleTo(Room room, ContentType type) {
        return type == ContentType.PLUGIN ? room.isPluginListVisible() : room.isModListVisible();
    }

    /**
     * 房主整表替换上报。本地目录是唯一事实来源，因此这里不做增量合并：
     * 先清空该房间记录再写入本次清单，本地删掉的文件在平台侧同步消失。
     */
    @Transactional
    public List<RoomContentDto> report(Long accountId, Long roomId, ReportRoomContentRequest request) {
        roomService.requireOwned(accountId, roomId);
        roomContentRepository.deleteByRoomId(roomId);
        LocalDateTime now = LocalDateTime.now();
        roomContentRepository.saveAll(request.items().stream()
                .map(item -> new RoomContent(roomId, item.name().trim(), item.type(),
                        item.sizeBytes(), item.enabled(), now))
                .toList());
        return list(accountId, roomId);
    }

    private static RoomContentDto toDto(RoomContent content) {
        return new RoomContentDto(content.getName(), content.getType(), content.getSizeBytes(),
                content.isEnabled(), content.getReportedAt());
    }
}