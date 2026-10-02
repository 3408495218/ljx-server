package com.ljx.server.announcement;

import com.ljx.server.announcement.dto.AnnouncementDtos.AnnouncementDto;
import com.ljx.server.announcement.dto.AnnouncementDtos.AnnouncementRequest;
import com.ljx.server.announcement.dto.AnnouncementDtos.PublicAnnouncementDto;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.audit.AuditAction;
import com.ljx.server.appconfig.AppConfig;
import com.ljx.server.appconfig.AppConfigRepository;
import com.ljx.server.common.audit.AuditService;
import com.ljx.server.common.exception.BizException;
import com.ljx.server.ws.LobbyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 公告：玩家端只读启用中的，后台可增删改。
 * <p>
 * 玩家端接口是**公开免登录**的（未登录用户也要能看到公告），
 * 所以只暴露 {@code id + content}，不泄露排序、开关等后台字段。
 */
@Service
public class AnnouncementService {

    private final AnnouncementRepository repository;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final AppConfigRepository appConfigRepository;

    public AnnouncementService(AnnouncementRepository repository,
                               AuditService auditService,
                               ApplicationEventPublisher eventPublisher,
                               AppConfigRepository appConfigRepository) {
        this.repository = repository;
        this.auditService = auditService;
        this.eventPublisher = eventPublisher;
        this.appConfigRepository = appConfigRepository;
    }

    /**
     * 公告变更后通知在线客户端立刻重拉。
     * <p>
     * 走的是房间广播那条通道（{@code /topic/lobby}），由 {@code LobbyBroadcaster} 在**事务提交后**发出，
     * 所以不会播到回滚掉的内容；推送失败不影响业务（客户端还有 5 分钟轮询兜底）。
     */
    private void broadcastChanged() {
        eventPublisher.publishEvent(new LobbyEvent(LobbyEvent.Type.ANNOUNCEMENT,
                null, null, 0, 0, false, null, null, null));
    }

    /** 玩家端：启用中的公告，按 sort_order 升序 */
    // ---------- 轮播间隔（后台可配） ----------

    private static final String KEY_ROTATE_SECONDS = "announcement_rotate_seconds";
    /** 下限 3 秒：再快玩家读不完；上限 120 秒：再慢会让人以为卡住了 */
    public static final int ROTATE_SECONDS_MIN = 3;
    public static final int ROTATE_SECONDS_MAX = 120;
    private static final int ROTATE_SECONDS_DEFAULT = 8;

    /** 当前轮播间隔（秒）；越界或非法值一律回落到默认 8 秒 */
    public int rotateSeconds() {
        int value = appConfigRepository.intValue(KEY_ROTATE_SECONDS, ROTATE_SECONDS_DEFAULT);
        if (value < ROTATE_SECONDS_MIN || value > ROTATE_SECONDS_MAX) {
            return ROTATE_SECONDS_DEFAULT;
        }
        return value;
    }

    /** 后台修改轮播间隔；改动会影响所有在线客户端，下一次拉公告即生效 */
    @Transactional
    public int updateRotateSeconds(int seconds) {
        if (seconds < ROTATE_SECONDS_MIN || seconds > ROTATE_SECONDS_MAX) {
            throw new BizException(ErrorCode.VALIDATION_FAILED,
                    "轮播间隔需在 " + ROTATE_SECONDS_MIN + "-" + ROTATE_SECONDS_MAX + " 秒之间");
        }
        AppConfig config = appConfigRepository.findById(KEY_ROTATE_SECONDS).orElse(null);
        if (config == null) {
            appConfigRepository.save(AppConfig.of(KEY_ROTATE_SECONDS, String.valueOf(seconds)));
        } else {
            config.setValue(String.valueOf(seconds));
        }
        return seconds;
    }

    public List<PublicAnnouncementDto> listEnabled() {
        return repository.findByEnabledTrueOrderBySortOrderAscIdAsc().stream()
                .map(item -> new PublicAnnouncementDto(item.getId(), item.getContent()))
                .toList();
    }

    /** 后台：全量（含已关闭） */
    public List<AnnouncementDto> listAll() {
        return repository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public AnnouncementDto create(Long adminId, AnnouncementRequest request) {
        String content = requireContent(request.content());
        Announcement saved = repository.save(new Announcement(
                content,
                request.sortOrder() == null ? 0 : request.sortOrder(),
                LocalDateTime.now()));
        auditService.record(null, AuditAction.ANNOUNCEMENT_CREATE, actor(adminId, saved.getContent()), true);
        broadcastChanged();
        return toDto(saved);
    }

    /** 局部更新：请求里为 null 的字段保持不变 */
    @Transactional
    public AnnouncementDto update(Long adminId, Long id, AnnouncementRequest request) {
        Announcement item = find(id);
        String content = request.content() == null ? null : requireContent(request.content());
        item.update(content, request.sortOrder(), request.enabled(), LocalDateTime.now());
        auditService.record(null, AuditAction.ANNOUNCEMENT_UPDATE, actor(adminId, item.getContent()), true);
        broadcastChanged();
        return toDto(item);
    }

    @Transactional
    public void delete(Long adminId, Long id) {
        Announcement item = find(id);
        repository.delete(item);
        auditService.record(null, AuditAction.ANNOUNCEMENT_DELETE, actor(adminId, item.getContent()), true);
        broadcastChanged();
    }

    private Announcement find(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.ANNOUNCEMENT_NOT_FOUND));
    }

    private String requireContent(String raw) {
        String content = raw == null ? "" : raw.trim();
        if (content.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_FAILED);
        }
        return content;
    }

    private AnnouncementDto toDto(Announcement item) {
        return new AnnouncementDto(item.getId(), item.getContent(), item.getSortOrder(),
                item.isEnabled(), item.getCreatedAt(), item.getUpdatedAt());
    }

    /**
     * 审计内容：管理员不是 account 实体，所以操作者写进 detail 而不是 accountId 字段；
     * 公告正文只记前 50 字，避免长公告把日志撑爆。
     */
    private String actor(Long adminId, String content) {
        String preview = content.length() <= 50 ? content : content.substring(0, 50) + "…";
        return "管理员#" + adminId + " " + preview;
    }
}
