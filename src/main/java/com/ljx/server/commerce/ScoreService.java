package com.ljx.server.commerce;

import com.ljx.server.appconfig.AppConfigRepository;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.commerce.entity.ScoreEvent;
import com.ljx.server.commerce.repository.ScoreEventRepository;
import com.ljx.server.level.LevelService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 互动经验：落流水 + 委托 {@link LevelService} 结算等级与配额。
 * <p>
 * **这里不再自己算等级、也不再自己改 score**。修复前它用 {@code LevelTable}（硬编码阈值）算等级、
 * 并把 score 当"累计总分"整体覆盖，与按 {@code level_config} 配置化的 {@code LevelService}
 * 写同一份数据却语义相反，导致等级经验反复横跳。现在：
 * <ul>
 *   <li>分值来源：{@code app_config}（键见 {@link ScoreEventType#configKey()}），后台可改；</li>
 *   <li>等级结算：只有 {@code LevelService} 一处；</li>
 *   <li>{@code score} 语义：**当前等级内的进度**（升级时扣掉阈值），全项目一致。</li>
 * </ul>
 */
@Service
public class ScoreService {

    private final AccountRepository accountRepository;
    private final ScoreEventRepository scoreEventRepository;
    private final AppConfigRepository appConfigRepository;
    private final LevelService levelService;

    public ScoreService(AccountRepository accountRepository,
                        ScoreEventRepository scoreEventRepository,
                        AppConfigRepository appConfigRepository,
                        LevelService levelService) {
        this.accountRepository = accountRepository;
        this.scoreEventRepository = scoreEventRepository;
        this.appConfigRepository = appConfigRepository;
        this.levelService = levelService;
    }

    /** 加分；账号不存在时静默跳过（事件来源均为已登录账号，异常数据不阻断主流程） */
    @Transactional
    public void award(Long accountId, ScoreEventType type) {
        if (accountRepository.findByIdAndDeletedAtIsNull(accountId).isEmpty()) {
            return;
        }
        // 分值从后台配置取（缺失时用枚举里的兜底值）
        int exp = appConfigRepository.intValue(type.configKey(), type.defaultDelta());
        if (exp <= 0) {
            return;
        }
        scoreEventRepository.save(new ScoreEvent(accountId, type, exp));
        levelService.grantExp(accountId, exp);
    }
}
