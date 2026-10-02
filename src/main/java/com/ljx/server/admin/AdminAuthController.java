package com.ljx.server.admin;

import com.ljx.server.admin.dto.AdminDtos.AdminLoginRequest;
import com.ljx.server.admin.dto.AdminDtos.AdminLoginResponse;
import com.ljx.server.admin.dto.AdminDtos.AdminMeResponse;
import com.ljx.server.admin.dto.AdminDtos.ChangePasswordRequest;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.common.api.ErrorCode;
import com.ljx.server.common.audit.AuditAction;
import com.ljx.server.common.audit.AuditService;
import com.ljx.server.common.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 管理端认证。
 * <p>
 * 登录是 {@code /api/admin/**} 里**唯一免鉴权**的路径，放行规则必须精确到
 * {@code /api/admin/auth/login}——写成通配 {@code /api/admin/auth/**} 会把 {@code /logout} 一起放行。
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "管理后台-认证")
public class AdminAuthController {

    private final AdminUserRepository adminUserRepository;
    private final AdminJwtService jwtService;
    private final AuditService auditService;
    /** 与 AuthService / EmailService 一致：直接持有实例，项目里没有 PasswordEncoder Bean */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AdminAuthController(AdminUserRepository adminUserRepository,
                               AdminJwtService jwtService,
                               AuditService auditService) {
        this.adminUserRepository = adminUserRepository;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    /**
     * 登录。加 @Transactional 是必须的：admin.markLogin(...) 改的是从仓储读出来的实体，
     * 没有事务时它是游离态，last_login_at 不会落库（实测漏过）。
     */
    @Operation(summary = "管理员登录")
    @Transactional
    @PostMapping("/auth/login")
    public ApiResponse<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest request) {
        if (adminUserRepository.count() == 0) {
            // 一个管理员都没有：说明部署时没配环境变量，直接给出可执行的下一步
            throw new BizException(ErrorCode.ADMIN_NOT_CONFIGURED);
        }
        // 账号不存在与密码错误返回同一错误码，不泄露"账号是否存在"
        AdminUser admin = adminUserRepository.findByUsername(request.username().trim())
                .filter(user -> passwordEncoder.matches(request.password(), user.getPasswordHash()))
                .orElseThrow(() -> {
                    auditService.record(null, AuditAction.ADMIN_LOGIN_FAILED, request.username(), false);
                    return new BizException(ErrorCode.BAD_CREDENTIALS);
                });

        admin.markLogin(LocalDateTime.now());
        auditService.record(null, AuditAction.ADMIN_LOGIN, admin.getUsername(), true);
        return ApiResponse.ok(new AdminLoginResponse(
                jwtService.issue(admin), admin.getUsername(), jwtService.ttl().toSeconds(),
                admin.isMustChangePassword()));
    }

    @Operation(summary = "当前管理员（校验令牌）")
    @GetMapping("/me")
    public ApiResponse<AdminMeResponse> me(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId) {
        AdminUser admin = adminUserRepository.findById(adminId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHENTICATED));
        return ApiResponse.ok(new AdminMeResponse(
                admin.getId(), admin.getUsername(), admin.isMustChangePassword()));
    }

    /**
     * 修改当前管理员密码。
     * <p>
     * 存在的意义：首个管理员是用环境变量初始化的，如果一直用初始密码（或被人猜到默认值），
     * 任何人都能进后台改商品、发 CDK、看数据库配置。
     * <p>
     * 注意：**已签发的令牌是无状态的，不会因此立刻失效**（最长 2 小时后自然过期）。
     * 对单管理员的小平台来说这个窗口可以接受；若要立即踢下线，得引入令牌黑名单或版本号。
     */
    @Operation(summary = "修改当前管理员密码")
    @Transactional
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @Valid @RequestBody ChangePasswordRequest request) {
        AdminUser admin = adminUserRepository.findById(adminId)
                .orElseThrow(() -> new BizException(ErrorCode.UNAUTHENTICATED));

        if (!passwordEncoder.matches(request.oldPassword(), admin.getPasswordHash())) {
            auditService.record(null, AuditAction.ADMIN_PASSWORD_CHANGE,
                    "管理员「" + admin.getUsername() + "」修改密码失败（当前密码不正确）", false);
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (request.oldPassword().equals(request.newPassword())) {
            throw new BizException(ErrorCode.VALIDATION_FAILED, "新密码不能与当前密码相同");
        }

        admin.changePassword(passwordEncoder.encode(request.newPassword()));
        auditService.record(null, AuditAction.ADMIN_PASSWORD_CHANGE,
                "管理员「" + admin.getUsername() + "」已修改密码（已签发的令牌最长 2 小时后失效）", true);
        return ApiResponse.ok();
    }

    @Operation(summary = "管理员登出（无状态，仅记审计）")
    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId) {
        auditService.record(null, AuditAction.ADMIN_LOGOUT, String.valueOf(adminId), true);
        return ApiResponse.ok();
    }
}
