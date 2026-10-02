package com.ljx.server.quota;

import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.exception.BizException;
import com.ljx.server.commerce.repository.VipPlanRepository;
import com.ljx.server.level.LevelConfigRepository;
import com.ljx.server.quota.entity.Quota;
import com.ljx.server.quota.repository.QuotaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 配额校验单点入口：所有配额校验都走这里。不受限类型（见 {@link QuotaType#unlimited()}）直接放行。
 */
@Service
public class QuotaService {

    private final QuotaRepository quotaRepository;
    private final AccountRepository accountRepository;
    private final VipPlanRepository vipPlanRepository;
    private final LevelConfigRepository levelConfigRepository;

    public QuotaService(QuotaRepository quotaRepository,
                        AccountRepository accountRepository,
                        VipPlanRepository vipPlanRepository,
                        LevelConfigRepository levelConfigRepository) {
        this.quotaRepository = quotaRepository;
        this.accountRepository = accountRepository;
        this.vipPlanRepository = vipPlanRepository;
        this.levelConfigRepository = levelConfigRepository;
    }

    /** 只读生效值，用于展示（不加锁，不加事务）；{@link QuotaType#UNLIMITED} 表示不限 */
    public int effective(Long accountId, QuotaType type) {
        // 优先级：VIP 档位 > 等级配置 > 配额表兜底
        Integer vipLimit = vipOverride(accountId, type);
        if (vipLimit != null) {
            return vipLimit;
        }
        Integer levelLimit = levelLimit(accountId, type);
        if (levelLimit != null) {
            return levelLimit;
        }
        return quotaRepository.findByAccountIdAndType(accountId, type)
                .map(Quota::getEffective)
                .orElseGet(type::base);
    }

    /**
     * VIP 档位对客户端压缩包上限的覆盖值。
     * <p>
     * 放在**读时判断**而不是购买时写进 quota 表：VIP 会过期，写进去还得有定时任务改回来，
     * 一旦任务没跑到就会出现"VIP 早过期了却仍享受 100MB"。读时判断永远和当前档位一致。
     *
     * @return 覆盖值；null 表示该类型不受 VIP 影响或当前无生效 VIP
     */
    /**
     * 等级配置里的上传上限（{@code level_config.upload_mb}），**由后台维护**。
     * <p>
     * 放在读时判断而不是把值写进 quota 表：等级与配置都可能变，
     * 写进去就得靠"升级时同步"来维持一致，一旦漏掉某条路径就会和配置不符。
     *
     * @return 覆盖值；null 表示该类型不受等级影响或缺配置（回落配额表）
     */
    private Integer levelLimit(Long accountId, QuotaType type) {
        if (type != QuotaType.CLIENT_PKG_MB || accountId == null) {
            return null;
        }
        Account account = accountRepository.findByIdAndDeletedAtIsNull(accountId).orElse(null);
        if (account == null) {
            return null;
        }
        return levelConfigRepository.findById(account.getLevel())
                .map(com.ljx.server.level.LevelConfig::getUploadMb)
                .orElse(null);
    }

    private Integer vipOverride(Long accountId, QuotaType type) {
        if (type != QuotaType.CLIENT_PKG_MB || accountId == null) {
            return null;
        }
        Account account = accountRepository.findByIdAndDeletedAtIsNull(accountId).orElse(null);
        if (account == null || account.getVipLevel() <= 0) {
            return null;
        }
        LocalDateTime expiresAt = account.getVipExpiresAt();
        if (expiresAt == null || expiresAt.isBefore(LocalDateTime.now())) {
            return null;
        }
        return vipPlanRepository.findById(account.getVipLevel())
                .map(plan -> plan.getPackageMaxMb())
                .orElse(null);
    }

    /** 值校验：结果值不得超过上限（容量、压缩包体积这类「量值」配额）；不受限类型不触库直接放行 */
    @Transactional
    public void check(Long accountId, QuotaType type, long value) {
        if (type.unlimited()) {
            return;
        }
        Integer vipLimit = vipOverride(accountId, type);
        if (vipLimit != null) {
            ensureWithin(vipLimit, value);
            return;
        }
        Integer levelLimit = levelLimit(accountId, type);
        if (levelLimit != null) {
            ensureWithin(levelLimit, value);
            return;
        }
        ensureWithin(lockEffective(accountId, type), value);
    }

    /** 纯校验，不触库 */
    public void ensureWithin(int limit, long value) {
        if (value > limit) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED, "超出配额上限（当前上限 " + limit + "）");
        }
    }

    /*
     * 这里原本有 applyLevelBase(accountId, level)：升级后把各配额的 base 重写成"按等级的上限表"。
     * 现在等级上限由 level_config.upload_mb 在**读时**决定（见 levelLimit），
     * 再往 quota 表里写一份只会造成"两个来源"，排查时说不清哪个生效，故删除。
     */

    /** 锁定该账号该类型的配额行并返回生效上限；锁随调用方事务提交释放 */
    private int lockEffective(Long accountId, QuotaType type) {
        return quotaRepository.findForUpdate(accountId, type)
                .map(Quota::getEffective)
                .orElseGet(type::base);
    }
}