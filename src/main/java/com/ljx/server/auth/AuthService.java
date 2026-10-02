package com.ljx.server.auth;

import com.ljx.server.auth.dto.AuthDtos.AccountDto;
import com.ljx.server.auth.dto.AuthDtos.TokenResponse;
import com.ljx.server.auth.entity.Account;
import com.ljx.server.auth.entity.EmailBinding;
import com.ljx.server.auth.entity.RefreshToken;
import com.ljx.server.auth.repository.AccountRepository;
import com.ljx.server.auth.repository.EmailBindingRepository;
import com.ljx.server.auth.repository.RefreshTokenRepository;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.audit.AuditAction;
import com.ljx.server.common.audit.AuditService;
import com.ljx.server.common.exception.BizException;
import com.ljx.server.quota.QuotaType;
import com.ljx.server.quota.entity.Quota;
import com.ljx.server.quota.repository.QuotaRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuthService {

    private final AccountRepository accountRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailBindingRepository emailBindingRepository;
    private final QuotaRepository quotaRepository;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final com.ljx.server.level.LevelService levelService;
    private final com.ljx.server.quota.QuotaService quotaService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(AccountRepository accountRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       EmailBindingRepository emailBindingRepository,
                       QuotaRepository quotaRepository,
                       JwtService jwtService,
                       AuditService auditService,
                       com.ljx.server.level.LevelService levelService,
                       com.ljx.server.quota.QuotaService quotaService) {
        this.accountRepository = accountRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.emailBindingRepository = emailBindingRepository;
        this.quotaRepository = quotaRepository;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.levelService = levelService;
        this.quotaService = quotaService;
    }

    @Transactional
    public TokenResponse register(String username, String password) {
        if (accountRepository.existsByUsernameAndDeletedAtIsNull(username)) {
            throw new BizException(ErrorCode.USERNAME_TAKEN);
        }
        Account account = new Account(username, passwordEncoder.encode(password));
        accountRepository.save(account);
        for (QuotaType type : QuotaType.values()) {
            quotaRepository.save(new Quota(account.getId(), type, type.base()));
        }
        auditService.record(account.getId(), AuditAction.REGISTER, username, true);
        return issueTokens(account, null);
    }

    /**
     * 访客注册：为「未登录也能开服」提供一个无感身份。
     * <p>
     * 为什么需要它：建房之后的改设置 / 删房 / 心跳 / 上传客户端包都要靠 accountId 判定归属，
     * 没有身份就没法安全地把房间交给创建者。这里直接发一个匿名账号，复用整套鉴权。
     * <p>
     * 密码是随机不可猜值 —— 这种账号**无法被登录**，它只承载"房主身份"。
     */
    @Transactional
    public TokenResponse registerGuest() {
        String username = nextGuestName();
        Account account = new Account(username, passwordEncoder.encode(randomSecret()));
        account.markAnonymous();
        accountRepository.save(account);
        for (QuotaType type : QuotaType.values()) {
            quotaRepository.save(new Quota(account.getId(), type, type.base()));
        }
        auditService.record(account.getId(), AuditAction.REGISTER, username + "（访客）", true);
        return issueTokens(account, null);
    }

    /** 生成不冲突的访客名：访客 + 6 位数字（撞名就重试，最多几次） */
    private String nextGuestName() {
        for (int i = 0; i < 8; i++) {
            String name = "访客" + String.format("%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(1_000_000));
            if (!accountRepository.existsByUsernameAndDeletedAtIsNull(name)) {
                return name;
            }
        }
        // 极端情况下退化为带时间戳的名字，保证一定能建出来
        return "访客" + System.currentTimeMillis() % 100_000_000L;
    }

    /** 32 字节随机串：访客账号用它做密码，保证"不可被登录" */
    private String randomSecret() {
        byte[] buf = new byte[32];
        new java.security.SecureRandom().nextBytes(buf);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    public TokenResponse login(String username, String password) {
        Account account = accountRepository.findByUsernameAndDeletedAtIsNull(username)
                .orElseThrow(() -> {
                    auditService.record(null, AuditAction.LOGIN_FAILED, username, false);
                    return new BizException(ErrorCode.BAD_CREDENTIALS);
                });
        if (account.isAnonymous()) {
            // 访客账号的密码是随机值，正常无法登录；显式拒绝并给出可理解的提示
            auditService.record(account.getId(), AuditAction.LOGIN_FAILED, username + "（访客）", false);
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (!passwordEncoder.matches(password, account.getPasswordHash())) {
            auditService.record(account.getId(), AuditAction.LOGIN_FAILED, username, false);
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        auditService.record(account.getId(), AuditAction.LOGIN, username, true);
        // 每天首次登录发一次经验（同一天重复登录不会再发，由 LevelService 按日期判断）
        var reward = levelService.claimDailyLogin(account.getId());
        return issueTokens(account, toRewardDto(reward));
    }

    /** 一次性轮换：旧 refresh 用过即废；重放已撤销 token 时撤销该账号全部会话 */
    @Transactional
    public TokenResponse refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtService.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }
        if (!JwtService.TYPE_REFRESH.equals(claims.get("typ", String.class))) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        RefreshToken stored = refreshTokenRepository
                .findByTokenHash(JwtService.sha256Hex(refreshToken))
                .orElseThrow(() -> new BizException(ErrorCode.REFRESH_TOKEN_INVALID));

        if (stored.getRevokedAt() != null) {
            refreshTokenRepository.revokeAllForAccount(stored.getAccountId());
            throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        Account account = accountRepository.findByIdAndDeletedAtIsNull(stored.getAccountId())
                .orElseThrow(() -> new BizException(ErrorCode.REFRESH_TOKEN_INVALID));

        stored.revoke();
        return issueTokens(account, null);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            jwtService.parse(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            return;
        }
        refreshTokenRepository.findByTokenHash(JwtService.sha256Hex(refreshToken))
                .ifPresent(RefreshToken::revoke);
    }

    public Account getAccount(Long accountId) {
        return accountRepository.findByIdAndDeletedAtIsNull(accountId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHENTICATED));
    }

    public AccountDto toAccountDto(Account account) {
        String email = emailBindingRepository.findById(account.getId())
                .filter(EmailBinding::isVerified)
                .map(EmailBinding::getEmail)
                .orElse(null);
        // 配额**必须走 QuotaService.effective()**：真实上限来自后台配置
        // （VIP → vip_plan.package_max_mb；否则 level_config.upload_mb），
        // 直接读 quota 表拿到的是旧的物化值，会出现"界面显示 30MB、实际只让传 5MB"这类不一致。
        Map<String, Integer> quotas = new LinkedHashMap<>();
        for (QuotaType type : QuotaType.values()) {
            quotas.put(type.name(), quotaService.effective(account.getId(), type));
        }
        return new AccountDto(account.getId(), account.getUsername(), account.getLevel(),
                account.getScore(), levelService.expToNext(account.getLevel()),
                account.getVipLevel(), account.getVipExpiresAt(), account.isAnonymous(),
                account.getCoins(), email, quotas);
    }

    /** 刷新/注册等场景不发经验，奖励传 null */
    private com.ljx.server.auth.dto.AuthDtos.DailyRewardDto toRewardDto(
            com.ljx.server.level.LevelService.DailyRewardResult result) {
        return new com.ljx.server.auth.dto.AuthDtos.DailyRewardDto(result.granted(),
                result.expGained(), result.level(), result.score(), result.levelsUp(), result.expToNext());
    }

    private TokenResponse issueTokens(Account account, com.ljx.server.auth.dto.AuthDtos.DailyRewardDto reward) {
        String refreshToken = jwtService.issueRefreshToken(account);
        LocalDateTime expiresAt = LocalDateTime.now().plus(jwtService.refreshTtl());
        refreshTokenRepository.save(new RefreshToken(account.getId(), JwtService.sha256Hex(refreshToken), expiresAt));
        return new TokenResponse(jwtService.issueAccessToken(account), refreshToken, toAccountDto(account), reward);
    }
}
