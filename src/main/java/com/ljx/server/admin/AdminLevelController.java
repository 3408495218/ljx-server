package com.ljx.server.admin;

import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.level.LevelConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台等级配置。
 * <p>
 * 「升到下一级需要多少经验」「该等级的客户端压缩包上传上限」「每天登录给多少经验」
 * 都由这里维护 —— 改完立刻生效（配额是读时取配置，不需要重启）。
 */
@RestController
@RequestMapping("/api/admin/levels")
@Tag(name = "管理后台-等级")
public class AdminLevelController {

    private final AdminLevelService adminLevelService;

    public AdminLevelController(AdminLevelService adminLevelService) {
        this.adminLevelService = adminLevelService;
    }

    public record LevelConfigDto(int level, int expToNext, int uploadMb, String note, boolean top) {
    }

    public record LevelOverviewDto(int dailyLoginExp, List<LevelConfigDto> levels) {
    }

    /** 改某级：null 表示该字段不变 */
    public record LevelUpdateRequest(
            @Min(value = 0, message = "经验不能为负") Integer expToNext,
            @Min(value = 0, message = "上传上限不能为负") Integer uploadMb,
            @Size(max = 64) String note) {
    }

    public record LevelCreateRequest(
            @Min(value = 0, message = "等级不能为负") int level,
            @Min(value = 0, message = "经验不能为负") int expToNext,
            @Min(value = 0, message = "上传上限不能为负") int uploadMb,
            @Size(max = 64) String note) {
    }

    public record DailyExpRequest(
            @Min(value = 0, message = "经验不能为负")
            @Max(value = 100000, message = "经验值过大") int exp) {
    }

    @Operation(summary = "等级总览（每日登录经验 + 各等级配置）")
    @GetMapping
    public ApiResponse<LevelOverviewDto> overview() {
        List<LevelConfigDto> levels = adminLevelService.list().stream()
                .map(this::toDto)
                .toList();
        return ApiResponse.ok(new LevelOverviewDto(adminLevelService.dailyLoginExp(), levels));
    }

    @Operation(summary = "修改某等级（升级所需经验 / 上传上限 / 备注；null 字段不变）")
    @PutMapping("/{level}")
    public ApiResponse<LevelConfigDto> update(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @PathVariable int level,
            @Valid @RequestBody LevelUpdateRequest request) {
        return ApiResponse.ok(toDto(adminLevelService.updateLevel(
                adminId, level, request.expToNext(), request.uploadMb(), request.note())));
    }

    @Operation(summary = "新增等级档（例如加开 6 级）")
    @PostMapping
    public ApiResponse<LevelConfigDto> create(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @Valid @RequestBody LevelCreateRequest request) {
        return ApiResponse.ok(toDto(adminLevelService.createLevel(adminId, request.level(),
                request.expToNext(), request.uploadMb(), request.note())));
    }

    @Operation(summary = "修改每日登录经验值")
    @PutMapping("/daily-exp")
    public ApiResponse<Integer> updateDailyExp(
            @RequestAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID) Long adminId,
            @Valid @RequestBody DailyExpRequest request) {
        return ApiResponse.ok(adminLevelService.updateDailyLoginExp(adminId, request.exp()));
    }

    private LevelConfigDto toDto(LevelConfig config) {
        return new LevelConfigDto(config.getLevel(), config.getExpToNext(),
                config.getUploadMb(), config.getNote(), config.isTop());
    }
}
