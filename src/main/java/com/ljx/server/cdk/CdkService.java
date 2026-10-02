package com.ljx.server.cdk;

import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.cdk.dto.CdkDtos.CdkBatchRequest;
import com.ljx.server.cdk.dto.CdkDtos.CdkBatchSummaryDto;
import com.ljx.server.cdk.dto.CdkDtos.CdkBatchResponse;
import com.ljx.server.cdk.dto.CdkDtos.CdkDto;
import com.ljx.server.cdk.dto.CdkDtos.RedeemResponse;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.audit.AuditAction;
import com.ljx.server.common.audit.AuditService;
import com.ljx.server.common.exception.BizException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CDK 兑换码：后台批量生成，玩家在商城兑换成金币。
 */
@Service
public class CdkService {

    /** 去掉了易混淆的 0/O/1/I，避免使用者手工输入时认错 */
    private static final String CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 16;
    private static final int MAX_BATCH = 500;

    private final CdkRepository cdkRepository;
    private final CdkRedeemRepository redeemRepository;
    private final AccountRepository accountRepository;
    private final AuditService auditService;
    private final SecureRandom random = new SecureRandom();

    public CdkService(CdkRepository cdkRepository,
                      CdkRedeemRepository redeemRepository,
                      AccountRepository accountRepository,
                      AuditService auditService) {
        this.cdkRepository = cdkRepository;
        this.redeemRepository = redeemRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    @Transactional
    public CdkBatchResponse createBatch(Long adminId, CdkBatchRequest request) {
        int count = Math.min(Math.max(request.count(), 1), MAX_BATCH);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = (request.validDays() == null || request.validDays() <= 0)
                ? null
                : now.plusDays(request.validDays());
        String batchNo = nextBatchNo(now);

        List<CdkDto> items = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Cdk saved = cdkRepository.save(new Cdk(nextUniqueCode(), request.coins(),
                    request.totalUses(), batchNo, expiresAt, now));
            items.add(toDto(saved));
        }
        auditService.record(null, AuditAction.CDK_BATCH_CREATE,
                "管理员#" + adminId + " 批次" + batchNo + " ×" + count + " 每个 " + request.coins() + " 金币", true);
        return new CdkBatchResponse(batchNo, items);
    }

    /**
     * 批次汇总：在数据库侧按 batch_no 聚合，一行一批。
     * 后台默认走这个视图——码多时平铺列表根本没法看。
     */
    public List<CdkBatchSummaryDto> listBatches() {
        return cdkRepository.summarizeBatches().stream().map(row -> {
            String batchNo = (String) row[0];
            return new CdkBatchSummaryDto(
                    batchNo,
                    ((Number) row[1]).intValue(),
                    ((Number) row[2]).intValue(),
                    ((Number) row[3]).intValue(),
                    row[4] == null ? 0 : ((Number) row[4]).intValue(),
                    (int) cdkRepository.countByBatchNoAndEnabledTrue(batchNo),
                    (LocalDateTime) row[5],
                    (LocalDateTime) row[6]);
        }).toList();
    }

    /** 某批的兑换码明细 */
    public List<CdkDto> listByBatch(String batchNo) {
        return cdkRepository.findByBatchNoOrderByIdAsc(batchNo).stream().map(this::toDto).toList();
    }

    public List<CdkDto> list() {
        return cdkRepository.findAllByOrderByIdDesc().stream().map(this::toDto).toList();
    }

    @Transactional
    public CdkDto toggle(Long adminId, Long id, boolean enabled) {
        Cdk cdk = find(id);
        cdk.setEnabled(enabled);
        auditService.record(null, AuditAction.CDK_TOGGLE,
                "管理员#" + adminId + " " + mask(cdk.getCode()) + " → " + (enabled ? "启用" : "停用"), true);
        return toDto(cdk);
    }

    /** 删除：已有领取记录的码不删（保留追溯），只能停用 */
    @Transactional
    public void delete(Long adminId, Long id) {
        Cdk cdk = find(id);
        if (redeemRepository.existsByCdkId(id)) {
            throw new BizException(ErrorCode.CDK_REDEEMED_CANNOT_DELETE);
        }
        cdkRepository.delete(cdk);
        auditService.record(null, AuditAction.CDK_DELETE, "管理员#" + adminId + " " + mask(cdk.getCode()), true);
    }

    /**
     * 玩家兑换。
     * <p>
     * 用 {@code findByCodeForUpdate} 先锁住这一行，再依次校验与占用名额——
     * 否则并发请求会同时读到"还有名额"而超领。
     */
    @Transactional
    public RedeemResponse redeem(Long accountId, String rawCode) {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        Cdk cdk = cdkRepository.findByCodeForUpdate(code)
                .orElseThrow(() -> new BizException(ErrorCode.CDK_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now();
        cdk.ensureActive(now);
        // 先判"本账号是否已领过"：同一个单次码重复兑换时应提示"已领取过"，
        // 而不是"码被领完"（两者对使用者含义不同），所以这一判必须排在名额之前
        if (redeemRepository.existsByCdkIdAndAccountId(cdk.getId(), accountId)) {
            throw new BizException(ErrorCode.CDK_ALREADY_REDEEMED);
        }
        cdk.ensureQuota();

        cdk.consumeOnce();
        redeemRepository.save(new CdkRedeem(cdk.getId(), accountId, cdk.getCoins(), now));

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHENTICATED));
        account.addCoins(cdk.getCoins());
        auditService.record(accountId, AuditAction.CDK_REDEEM,
                mask(code) + " +" + cdk.getCoins() + " 金币", true);
        return new RedeemResponse(account.getCoins(), cdk.getCoins());
    }

    private Cdk find(Long id) {
        return cdkRepository.findById(id)
                .orElseThrow(() -> new BizException(ErrorCode.CDK_NOT_FOUND));
    }

    /**
     * 批次号：时间戳 + 4 位随机后缀。
     * <p>
     * 只用秒级时间戳会在"同一秒内连续生成两批"时撞号，导致两批被并成一批
     * （实测踩到：生成 3 个和 2 个的两批汇总成了一行 5 个、钻石还取了较大值）。
     */
    private String nextBatchNo(LocalDateTime now) {
        String stamp = String.format("%04d%02d%02d%02d%02d%02d",
                now.getYear(), now.getMonthValue(), now.getDayOfMonth(),
                now.getHour(), now.getMinute(), now.getSecond());
        StringBuilder suffix = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            suffix.append(CHARSET.charAt(random.nextInt(CHARSET.length())));
        }
        return "B" + stamp + suffix;
    }

    /** 生成唯一码；撞了重试，唯一约束是最后一道防线 */
    private String nextUniqueCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CHARSET.charAt(random.nextInt(CHARSET.length())));
            }
            String code = sb.toString();
            if (!cdkRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR);
    }

    private CdkDto toDto(Cdk cdk) {
        return new CdkDto(cdk.getId(), cdk.getCode(), cdk.getCoins(), cdk.getTotalUses(),
                cdk.getUsedUses(), cdk.getBatchNo(), cdk.isEnabled(), cdk.getExpiresAt(), cdk.getCreatedAt());
    }

    /** 审计与日志里只留下首尾，避免完整兑换码被日志泄露 */
    private String mask(String code) {
        if (code.length() <= 6) {
            return code;
        }
        return code.substring(0, 3) + "****" + code.substring(code.length() - 3);
    }
}
