package com.ljx.server.admin;

import com.ljx.server.appconfig.AppConfig;
import com.ljx.server.appconfig.AppConfigRepository;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.audit.AuditAction;
import com.ljx.server.common.audit.AuditService;
import com.ljx.server.common.exception.BizException;
import com.ljx.server.level.LevelConfig;
import com.ljx.server.level.LevelConfigRepository;
import com.ljx.server.level.LevelService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 后台等级配置：**升到下一级需要多少经验、该等级允许多大的客户端压缩包、每天登录给多少经验**，
 * 全部由这里维护，改完立刻对各等级的配额校验生效（配额是读时取配置，不需要重启）。
 */
@Service
public class AdminLevelService {

    /** 每日登录经验的配置键（与 LevelService 用同一个） */
    private static final String KEY_DAILY_LOGIN_EXP = LevelService.KEY_DAILY_LOGIN_EXP;

    private final LevelConfigRepository levelConfigRepository;
    private final AppConfigRepository appConfigRepository;
    private final AuditService auditService;

    public AdminLevelService(LevelConfigRepository levelConfigRepository,
                             AppConfigRepository appConfigRepository,
                             AuditService auditService) {
        this.levelConfigRepository = levelConfigRepository;
        this.appConfigRepository = appConfigRepository;
        this.auditService = auditService;
    }

    public List<LevelConfig> list() {
        return levelConfigRepository.findAllByOrderByLevelAsc();
    }

    public int dailyLoginExp() {
        return appConfigRepository.intValue(KEY_DAILY_LOGIN_EXP, 1);
    }

    @Transactional
    public LevelConfig updateLevel(Long adminId, int level, Integer expToNext, Integer uploadMb, String note) {
        LevelConfig config = levelConfigRepository.findById(level)
                .orElseThrow(() -> new BizException(ErrorCode.LEVEL_CONFIG_NOT_FOUND));
        config.update(expToNext, uploadMb, note);
        auditService.record(null, AuditAction.LEVEL_CONFIG_UPDATE,
                "管理员#" + adminId + " 等级 " + level + " → 升级需 " + config.getExpToNext()
                        + " 经验，上传上限 " + config.getUploadMb() + "MB", true);
        return config;
    }

    /** 新增一个等级档（例如后台想加开 6 级） */
    @Transactional
    public LevelConfig createLevel(Long adminId, int level, int expToNext, int uploadMb, String note) {
        if (levelConfigRepository.existsById(level)) {
            throw new BizException(ErrorCode.LEVEL_CONFIG_EXISTS);
        }
        LevelConfig saved = levelConfigRepository.save(
                LevelConfig.create(level, expToNext, uploadMb, note));
        auditService.record(null, AuditAction.LEVEL_CONFIG_UPDATE,
                "管理员#" + adminId + " 新增等级 " + level + "（升级需 " + expToNext
                        + " 经验，上传上限 " + uploadMb + "MB）", true);
        return saved;
    }

    @Transactional
    public int updateDailyLoginExp(Long adminId, int exp) {
        AppConfig config = appConfigRepository.findById(KEY_DAILY_LOGIN_EXP)
                .orElseThrow(() -> new BizException(ErrorCode.LEVEL_CONFIG_NOT_FOUND));
        config.setValue(String.valueOf(exp));
        auditService.record(null, AuditAction.LEVEL_CONFIG_UPDATE,
                "管理员#" + adminId + " 每日登录经验改为 " + exp, true);
        return exp;
    }
}
