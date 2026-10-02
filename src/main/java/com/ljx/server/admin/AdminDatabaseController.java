package com.ljx.server.admin;

import com.ljx.server.admin.dto.DatabaseDtos.DatabaseSnippetResponse;
import com.ljx.server.admin.dto.DatabaseDtos.DatabaseStatusDto;
import com.ljx.server.admin.dto.DatabaseDtos.DatabaseTestRequest;
import com.ljx.server.admin.dto.DatabaseDtos.DatabaseTestResponse;
import com.ljx.server.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据库面板。
 * <p>
 * 只有三个动作：**看现状 / 当场试连 / 生成配置片段**。
 * 没有"保存并生效"——理由见 {@link DatabaseAdminService} 的类注释（改数据源必须重启，且写错会自锁）。
 */
@RestController
@RequestMapping("/api/admin/database")
@Tag(name = "管理后台-数据库")
public class AdminDatabaseController {

    private final DatabaseAdminService databaseAdminService;

    public AdminDatabaseController(DatabaseAdminService databaseAdminService) {
        this.databaseAdminService = databaseAdminService;
    }

    @Operation(summary = "当前生效的数据库配置与连接池状态（密码不返回）")
    @GetMapping
    public ApiResponse<DatabaseStatusDto> status() {
        return ApiResponse.ok(databaseAdminService.status());
    }

    @Operation(summary = "测试连接（临时试连，不落库、不写文件、不改数据源）")
    @PostMapping("/test")
    public ApiResponse<DatabaseTestResponse> test(@Valid @RequestBody DatabaseTestRequest request) {
        return ApiResponse.ok(databaseAdminService.test(request));
    }

    @Operation(summary = "生成部署配置片段（含环境变量清单）")
    @PostMapping("/snippet")
    public ApiResponse<DatabaseSnippetResponse> snippet(@Valid @RequestBody DatabaseTestRequest request) {
        return ApiResponse.ok(databaseAdminService.snippet(request));
    }
}
