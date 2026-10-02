package com.ljx.server.level;

import com.ljx.server.appconfig.AppConfigRepository;
import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 普通玩家等级机制。
 * <p>
 * 规则（阈值与上传上限都由后台配置，见 level_config）：
 * <ol>
 *   <li>**每天登录一次获得一次经验**（经验值也由后台配置，键 daily_login_exp）；</li>
 *   <li>累积够"升到下一级所需经验"就升级，**升级时扣掉该级所需经验**，所以
 *       {@code account.score} 表示的是**当前等级内的进度**；</li>
 *   <li>升级后按新等级刷新配额（客户端压缩包上传上限由 level_config.upload_mb 决定）。</li>
 * </ol>
 */
@Service
public class LevelService {

    private static final Logger log = LoggerFactory.getLogger(LevelService.class);

    /** 每日登录经验的配置键 */
    public static final String KEY_DAILY_LOGIN_EXP = "daily_login_exp";

    /** 防止配置写错导致死循环：单次结算最多连升这么多级 */
    private static final int MAX_LEVELS_PER_SETTLE = 100;

    private final AccountRepository accountRepository;
    private final LevelConfigRepository levelConfigRepository;
    private final AppConfigRepository appConfigRepository;

    public LevelService(AccountRepository accountRepository,
                        LevelConfigRepository levelConfigRepository,
                        AppConfigRepository appConfigRepository) {
        this.accountRepository = accountRepository;
        this.levelConfigRepository = levelConfigRepository;
        this.appConfigRepository = appConfigRepository;
    }

    /** 每日登录经验的结果，前端可直接展示"今天加了 N 点经验 / 是否升级" */
    public record DailyRewardResult(
            boolean granted,
            int expGained,
            int level,
            int score,
            int levelsUp,
            int expToNext) {
    }

    /**
     * 领取每日登录经验：**同一天只发一次**（按日期判断，避免时分秒干扰）。
     * 登录成功后调用；重复调用是安全的。
     */
    @Transactional
    public DailyRewardResult claimDailyLogin(Long accountId) {
        Account account = accountRepository.findByIdAndDeletedAtIsNull(accountId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHENTICATED));
        LocalDate today = LocalDate.now();

        if (today.equals(account.getLastDailyRewardOn())) {
            return snapshot(account, false, 0, 0);
        }

        int exp = appConfigRepository.intValue(KEY_DAILY_LOGIN_EXP, 1);
        int levelBefore = account.getLevel();
        account.markDailyReward(today);
        account.addScore(exp);
        int levelAfter = settleLevel(account);
        if (levelAfter != levelBefore) {
            log.info("账号 {} 升级：{} -> {}（每日登录 +{} 经验）", accountId, levelBefore, levelAfter, exp);
        }
        return snapshot(account, true, exp, levelAfter - levelBefore);
    }

    /**
     * 通用加分入口：**所有经验来源都走这里**（每日登录、建房、被加入游戏、被收藏…），
     * 保证"加经验 → 按 level_config 结算等级 → 刷新按等级分档的配额"三步同事务，
     * 且只有一套阈值语义。
     *
     * @param exp 本次加多少经验；<= 0 直接忽略
     * @return 结算后的等级
     */
    @Transactional
    public int grantExp(Long accountId, int exp) {
        if (exp <= 0) {
            return accountRepository.findByIdAndDeletedAtIsNull(accountId)
                    .map(Account::getLevel)
                    .orElse(0);
        }
        Account account = accountRepository.findByIdAndDeletedAtIsNull(accountId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHENTICATED));
        int levelBefore = account.getLevel();
        account.addScore(exp);
        int levelAfter = settleLevel(account);
        if (levelAfter != levelBefore) {
            log.info("账号 {} 升级：{} -> {}（+{} 经验）", accountId, levelBefore, levelAfter, exp);
        }
        return levelAfter;
    }

    /**
     * 按配置把经验换算成等级：够一级就升一级、并扣掉该级所需经验，支持一次连升多级。
     * 配置缺失或已封顶（expToNext <= 0）即停止。
     */
    @Transactional
    public int settleLevel(Account account) {
        int level = account.getLevel();
        for (int guard = 0; guard < MAX_LEVELS_PER_SETTLE; guard++) {
            Optional<LevelConfig> config = levelConfigRepository.findById(level);
            if (config.isEmpty() || config.get().isTop()) {
                break;
            }
            if (account.getScore() < config.get().getExpToNext()) {
                break;
            }
            account.addScore(-config.get().getExpToNext());
            level++;
        }
        account.setLevel(level);
        // 不需要"同步配额"：客户端压缩包上限由 level_config.upload_mb 在读时决定
        // （见 QuotaService.levelLimit），等级一变下一次校验自然就是新上限。
        return level;
    }

    /** 当前等级"升到下一级还需要多少经验"；已封顶返回 0 */
    public int expToNext(int level) {
        return levelConfigRepository.findById(level)
                .filter(config -> !config.isTop())
                .map(LevelConfig::getExpToNext)
                .orElse(0);
    }

    private DailyRewardResult snapshot(Account account, boolean granted, int expGained, int levelsUp) {
        return new DailyRewardResult(granted, expGained, account.getLevel(), account.getScore(),
                levelsUp, expToNext(account.getLevel()));
    }
}
