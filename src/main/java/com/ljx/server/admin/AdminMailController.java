package com.ljx.server.admin;

import com.ljx.server.auth.MailConfigService;
import com.ljx.server.auth.MailSender;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.common.audit.AuditAction;
import com.ljx.server.common.audit.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台邮件（SMTP）配置。
 * <p>
 * 改完**立刻生效**：发送时才解析配置，不依赖重启。
 * 优先级为 **环境变量 > 后台配置**，面板会显示当前生效来源。
 * **密码永不回显**，只告诉使用者"是否已设置"；保存时留空表示保持原值。
 */
@RestController
@RequestMapping("/api/admin/mail")
@Tag(name = "管理后台-邮件")
public class AdminMailController {

    private static final Logger log = LoggerFactory.getLogger(AdminMailController.class);

    private final MailConfigService mailConfigService;
    private final MailSender mailSender;
    private final AuditService auditService;

    public AdminMailController(MailConfigService mailConfigService,
                               MailSender mailSender,
                               AuditService auditService) {
        this.mailConfigService = mailConfigService;
        this.mailSender = mailSender;
        this.auditService = auditService;
    }

    /** 配置视图：**不含密码**，只给"是否已设置"与"当前生效来源" */
    public record MailConfigDto(
            boolean enabled,
            String host,
            int port,
            String username,
            boolean passwordSet,
            String from,
            boolean sslEnabled,
            boolean envProvided,
            boolean configured,
            String source) {
    }

    /** 保存配置；password 传 null 表示保持原值不变（面板不回显密码） */
    public record MailConfigRequest(
            Boolean enabled,
            @Size(max = 160) String host,
            @Min(value = 1, message = "端口不合法") @jakarta.validation.constraints.Max(value = 65535, message = "端口不合法") Integer port,
            @Size(max = 160) String username,
            @Size(max = 160) String password,
            @Size(max = 160) String from,
            Boolean sslEnabled) {
    }

    public record MailTestRequest(@NotBlank @Email(message = "请输入正确的邮箱地址") String to) {
    }

    public record MailTestResponse(boolean ok, String message) {
    }

    @Operation(summary = "读取 SMTP 配置（密码不返回，只给是否已设置与生效来源）")
    @GetMapping
    public ApiResponse<MailConfigDto> get() {
        var current = mailConfigService.current();
        return ApiResponse.ok(new MailConfigDto(
                mailConfigService.storedEnabled(),
                mailConfigService.storedHost(),
                mailConfigService.storedPort(),
                mailConfigService.storedUsername(),
                mailConfigService.passwordSet(),
                mailConfigService.storedFrom(),
                mailConfigService.storedSsl(),
                mailConfigService.envProvided(),
                current.isPresent(),
                current.map(MailConfigService.SmtpSettings::source).orElse("未配置")));
    }

    @Operation(summary = "保存 SMTP 配置（改完立刻生效；password 留空表示保持原值）")
    @PutMapping
    public ApiResponse<MailConfigDto> save(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @Valid @RequestBody MailConfigRequest request) {
        mailConfigService.save(
                request.enabled() != null && request.enabled(),
                request.host(), request.port(), request.username(), request.password(),
                request.from(), request.sslEnabled());
        auditService.record(null, AuditAction.MAIL_CONFIG_UPDATE,
                "管理员#" + adminId + " 更新 SMTP 配置：host=" + request.host()
                        + "，密码" + (request.password() == null || request.password().isBlank()
                        ? "保持不变" : "已更新"), true);
        return get();
    }

    @Operation(summary = "发送测试邮件（用当前生效的配置真发一封）")
    @PostMapping("/test")
    public ApiResponse<MailTestResponse> test(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @Valid @RequestBody MailTestRequest request) {
        try {
            mailSender.send(request.to(), "垃圾侠 SMTP 测试邮件", "123456");
            auditService.record(null, AuditAction.MAIL_CONFIG_UPDATE,
                    "管理员#" + adminId + " SMTP 测试发信成功 → " + request.to(), true);
            return ApiResponse.ok(new MailTestResponse(true, "测试邮件已发出，请到 " + request.to() + " 查收"));
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("SMTP 测试发信失败：{}", reason);
            auditService.record(null, AuditAction.MAIL_CONFIG_UPDATE,
                    "管理员#" + adminId + " SMTP 测试发信失败 → " + request.to(), false);
            return ApiResponse.ok(new MailTestResponse(false, "发送失败：" + reason));
        }
    }
}
