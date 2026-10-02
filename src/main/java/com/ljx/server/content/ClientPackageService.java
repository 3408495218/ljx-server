package com.ljx.server.content;

import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.audit.AuditAction;
import com.ljx.server.common.audit.AuditService;
import com.ljx.server.common.exception.BizException;
import com.ljx.server.content.dto.ContentDtos.ClientPackageDto;
import com.ljx.server.content.entity.ClientPackage;
import com.ljx.server.content.repository.ClientPackageRepository;
import com.ljx.server.quota.QuotaService;
import com.ljx.server.quota.QuotaType;
import com.ljx.server.room.RoomService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 客户端压缩包：房主上传到平台，玩家在「启动游戏」时下载后交给 PCL 导入。
 * 本软件不参与打包，平台只做存储与分发，大小上限由等级决定（见 {@link QuotaType#CLIENT_PKG_MB}）。
 */
@Service
public class ClientPackageService {

    private static final long MB = 1024L * 1024L;

    private final RoomService roomService;
    private final ClientPackageRepository repository;
    private final QuotaService quotaService;
    private final AuditService auditService;
    private final Path root;

    public ClientPackageService(RoomService roomService,
                               ClientPackageRepository repository,
                               QuotaService quotaService,
                               AuditService auditService,
                               @Value("${ljx.storage.dir}") String storageDir) {
        this.roomService = roomService;
        this.repository = repository;
        this.quotaService = quotaService;
        this.auditService = auditService;
        this.root = Paths.get(storageDir).resolve("client-packages");
    }

    /** 元数据；尚未上传返回 null */
    @Transactional(readOnly = true)
    public ClientPackageDto meta(Long roomId) {
        return repository.findByRoomId(roomId).map(ClientPackageService::toDto).orElse(null);
    }

    /** 上传即替换：同一房间只保留最新一份 */
    @Transactional
    public ClientPackageDto upload(Long accountId, Long roomId, MultipartFile file) {
        roomService.requireOwned(accountId, roomId);
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "请选择要上传的压缩包");
        }
        String fileName = sanitizeFileName(file.getOriginalFilename());
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "客户端包仅支持 zip 压缩包");
        }
        long size = file.getSize();
        // 先按等级上限拦截，避免写完盘再回滚
        quotaService.check(accountId, QuotaType.CLIENT_PKG_MB, ceilMb(size));

        Path target = pathOf(roomId);
        try {
            Files.createDirectories(root);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "保存客户端包失败：" + e.getMessage());
        }

        LocalDateTime now = LocalDateTime.now();
        ClientPackage saved = repository.findByRoomId(roomId)
                .map(existing -> {
                    existing.replace(fileName, size, now);
                    return existing;
                })
                .orElseGet(() -> new ClientPackage(roomId, fileName, size, now));
        saved = repository.save(saved);
        auditService.record(accountId, AuditAction.CLIENT_PACKAGE_UPLOAD, fileName, true);
        return toDto(saved);
    }

    @Transactional
    public void delete(Long accountId, Long roomId) {
        roomService.requireOwned(accountId, roomId);
        repository.deleteByRoomId(roomId);
        try {
            Files.deleteIfExists(pathOf(roomId));
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "删除客户端包失败：" + e.getMessage());
        }
        auditService.record(accountId, AuditAction.CLIENT_PACKAGE_DELETE, "room=" + roomId, true);
    }

    /** 下载：登录即可取，不校验归属（玩家要下载房主的包） */
    @Transactional(readOnly = true)
    public ClientPackageFile load(Long roomId) {
        ClientPackage meta = repository.findByRoomId(roomId)
                .orElseThrow(() -> new BizException(ErrorCode.CONTENT_NOT_FOUND, "房主尚未上传客户端压缩包"));
        Path path = pathOf(roomId);
        if (!Files.isRegularFile(path)) {
            throw new BizException(ErrorCode.CONTENT_NOT_FOUND, "客户端压缩包文件缺失，请房主重新上传");
        }
        return new ClientPackageFile(meta.getFileName(), path, meta.getSizeBytes());
    }

    private Path pathOf(Long roomId) {
        return root.resolve(roomId + ".zip");
    }

    /** 只保留文件名本身，剥掉浏览器可能带上的路径，避免写出到目标目录之外 */
    private static String sanitizeFileName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "无法识别压缩包文件名");
        }
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.trim();
        if (name.isEmpty() || name.contains("..")) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "压缩包文件名不合法");
        }
        return name;
    }

    private static long ceilMb(long bytes) {
        return (bytes + MB - 1) / MB;
    }

    private static ClientPackageDto toDto(ClientPackage entity) {
        return new ClientPackageDto(entity.getFileName(), entity.getSizeBytes(), entity.getUploadedAt());
    }

    /** 下载所需的最小信息：展示用文件名 + 磁盘路径 */
    public record ClientPackageFile(String fileName, Path path, long sizeBytes) {
    }
}