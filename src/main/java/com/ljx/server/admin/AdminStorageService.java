package com.ljx.server.admin;

import com.ljx.server.room.repository.RoomRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 后台「存储」面板的数据来源。
 * <p>
 * 存在的意义：客户端压缩包默认落在**系统临时目录**（`${java.io.tmpdir}/ljx-storage`），
 * Linux 上 `/tmp` 重启会被清空 → 所有房间的客户端包全丢。这个面板让人**一眼看出**
 * 当前目录是不是临时目录，并在页面顶部给红色告警；同时给出占用与"孤儿包"数量
 * （房间已删除但文件还留着），便于清理磁盘。
 */
@Service
public class AdminStorageService {

    private final Path root;
    private final String configuredDir;
    private final RoomRepository roomRepository;

    public AdminStorageService(@Value("${ljx.storage.dir}") String storageDir,
                               RoomRepository roomRepository) {
        this.configuredDir = storageDir;
        this.root = Paths.get(storageDir).resolve("client-packages");
        this.roomRepository = roomRepository;
    }

    /** 后台展示用的存储概况 */
    public record StorageInfoDto(
            String dir,
            boolean tempDir,
            int packageFiles,
            long packageBytes,
            long roomCount,
            int orphanFiles,
            long freeBytes) {
    }

    public StorageInfoDto info() {
        int files = 0;
        long bytes = 0;
        int orphans = 0;

        if (Files.isDirectory(root)) {
            // 先扫目录、收集文件名里的房间号，再**按需**批量查存在性
            // （原先用 roomRepository.findAll() 把所有房间都捞出来，房间多时是明显的浪费）
            List<Long> candidateRoomIds = new ArrayList<>();
            List<Path> zipFiles = new ArrayList<>();
            try (Stream<Path> stream = Files.list(root)) {
                for (Path path : stream.toList()) {
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    files++;
                    try {
                        bytes += Files.size(path);
                    } catch (IOException ignored) {
                        // 单个文件读不到大小不影响整体统计
                    }
                    String name = path.getFileName().toString();
                    if (!name.endsWith(".zip")) {
                        continue;
                    }
                    zipFiles.add(path);
                    try {
                        candidateRoomIds.add(Long.parseLong(name.substring(0, name.length() - 4)));
                    } catch (NumberFormatException ignored) {
                        // 不是按房间号命名的文件，不计入孤儿包
                    }
                }
            } catch (IOException ignored) {
                // 目录不可读时返回 0，不让面板报错
            }
            if (!candidateRoomIds.isEmpty()) {
                Set<Long> existing = roomRepository.findAllById(candidateRoomIds).stream()
                        .map(room -> room.getId())
                        .collect(Collectors.toSet());
                for (Long roomId : candidateRoomIds) {
                    if (!existing.contains(roomId)) {
                        orphans++;
                    }
                }
            }
        }

        long free = -1;
        try {
            Path probe = Files.exists(root) ? root : Paths.get(configuredDir);
            if (Files.exists(probe)) {
                free = Files.getFileStore(probe).getUsableSpace();
            }
        } catch (IOException ignored) {
            // 取不到剩余空间就返回 -1，前端显示"未知"
        }

        return new StorageInfoDto(configuredDir, isTempDir(), files, bytes,
                roomRepository.count(), orphans, free);
    }

    /** 存储目录是否位于系统临时目录之下（危险：重启/系统清理会丢包） */
    private boolean isTempDir() {
        String tmp = System.getProperty("java.io.tmpdir");
        if (tmp == null) {
            return false;
        }
        try {
            String base = Paths.get(tmp).toAbsolutePath().normalize().toString()
                    .replace('\\', '/').toLowerCase(Locale.ROOT);
            String actual = Paths.get(configuredDir).toAbsolutePath().normalize().toString()
                    .replace('\\', '/').toLowerCase(Locale.ROOT);
            return actual.startsWith(base);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
