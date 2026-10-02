package com.ljx.server.auth;

import com.ljx.server.auth.dto.AuthDtos.AccountDto;
import com.ljx.server.auth.dto.AuthDtos.BindEmailRequest;
import com.ljx.server.auth.dto.AuthDtos.SendCodeRequest;
import com.ljx.server.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "me", description = "账号信息与邮箱绑定")
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final AuthService authService;
    private final EmailService emailService;

    public MeController(AuthService authService, EmailService emailService) {
        this.authService = authService;
        this.emailService = emailService;
    }

    @Operation(summary = "账号信息 + 各类配额生效值")
    @GetMapping
    public ApiResponse<AccountDto> me(@RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId) {
        return ApiResponse.ok(authService.toAccountDto(authService.getAccount(accountId)));
    }

    @Operation(summary = "发送邮箱验证码（开发期验证码输出到服务端日志）")
    @PostMapping("/email/code")
    public ApiResponse<Void> sendCode(@RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
                                      @Valid @RequestBody SendCodeRequest request) {
        emailService.sendCode(accountId, request.email());
        return ApiResponse.ok();
    }

    @Operation(summary = "绑定邮箱")
    @PutMapping("/email")
    public ApiResponse<AccountDto> bindEmail(@RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
                                             @Valid @RequestBody BindEmailRequest request) {
        emailService.bind(accountId, request.email(), request.code());
        return ApiResponse.ok(authService.toAccountDto(authService.getAccount(accountId)));
    }
}
