package com.ljx.server.admin;

import com.ljx.server.admin.AdminStorageService.StorageInfoDto;
import com.ljx.server.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 存储概况：客户端压缩包放在哪、占多少、是否落在临时目录（会丢包）。 */
@RestController
@RequestMapping("/api/admin/storage")
@Tag(name = "管理后台-存储")
public class AdminStorageController {

    private final AdminStorageService adminStorageService;

    public AdminStorageController(AdminStorageService adminStorageService) {
        this.adminStorageService = adminStorageService;
    }

    @Operation(summary = "存储概况（目录 / 占用 / 孤儿包 / 是否临时目录）")
    @GetMapping
    public ApiResponse<StorageInfoDto> info() {
        return ApiResponse.ok(adminStorageService.info());
    }
}
