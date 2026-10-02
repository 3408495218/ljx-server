package com.ljx.server.snapshot;

import com.ljx.server.room.RoomService;
import com.ljx.server.snapshot.dto.SnapshotDtos.CreateSnapshotRequest;
import com.ljx.server.snapshot.dto.SnapshotDtos.SnapshotDto;
import com.ljx.server.snapshot.entity.Snapshot;
import com.ljx.server.snapshot.repository.SnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 快照元数据：仅房主可登记与查看；快照文件保留在房主本机。
 * <p>
 * 云端登记与本地文件以**名称**对齐（本地快照就是 `<工作目录>/ljx-snapshots/<名称>.zip`），
 * 因此登记按名称 upsert、删除按名称幂等，本地增删后重连不会留下孤儿记录或重复行。
 */
@Service
public class SnapshotService {

    private final RoomService roomService;
    private final SnapshotRepository snapshotRepository;

    public SnapshotService(RoomService roomService, SnapshotRepository snapshotRepository) {
        this.roomService = roomService;
        this.snapshotRepository = snapshotRepository;
    }

    @Transactional(readOnly = true)
    public List<SnapshotDto> list(Long accountId, Long roomId) {
        roomService.requireOwned(accountId, roomId);
        return listInternal(roomId);
    }

    /** 登记（幂等）：同名快照只保留一条，重复登记刷新大小与时间 */
    @Transactional
    public SnapshotDto create(Long accountId, Long roomId, CreateSnapshotRequest request) {
        roomService.requireOwned(accountId, roomId);
        String name = request.name().trim();
        Snapshot snapshot = snapshotRepository.findByRoomIdAndName(roomId, name)
                .or(() -> snapshotRepository.findByRoomIdOrderByIdDesc(roomId).stream()
                        .filter(item -> item.getName().equalsIgnoreCase(name))
                        .findFirst())
                .orElseGet(() -> new Snapshot(roomId, name, request.sizeBytes()));
        snapshot.updateSize(request.sizeBytes());
        return toDto(snapshotRepository.save(snapshot));
    }

    /** 删除（幂等）：未登记过同名快照时同样返回成功；返回删除后的余下列表 */
    @Transactional
    public List<SnapshotDto> delete(Long accountId, Long roomId, String name) {
        roomService.requireOwned(accountId, roomId);
        String target = name.trim();
        snapshotRepository.deleteByRoomIdAndName(roomId, target);
        // 名称大小写可能与本地文件不一致，兜底再按忽略大小写清一遍
        snapshotRepository.findByRoomIdAndName(roomId, target)
                .ifPresent(found -> snapshotRepository.deleteById(found.getId()));
        return listInternal(roomId).stream()
                .filter(item -> !item.name().equalsIgnoreCase(target))
                .toList();
    }

    private List<SnapshotDto> listInternal(Long roomId) {
        return snapshotRepository.findByRoomIdOrderByIdDesc(roomId).stream()
                .map(SnapshotService::toDto)
                .toList();
    }

    private static SnapshotDto toDto(Snapshot snapshot) {
        return new SnapshotDto(snapshot.getId(), snapshot.getName(), snapshot.getSizeBytes(),
                snapshot.getCreatedAt());
    }
}
