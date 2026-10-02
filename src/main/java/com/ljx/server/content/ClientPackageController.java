package com.ljx.server.content;

import com.ljx.server.auth.AuthInterceptor;
import com.ljx.server.common.api.ApiResponse;
import com.ljx.server.content.ClientPackageService.ClientPackageFile;
import com.ljx.server.content.dto.ContentDtos.ClientPackageDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/** 客户端压缩包：房主上传 / 删除，玩家下载后交给 PCL 导入 */
@RestController
@RequestMapping("/api/rooms/{roomId}/client-package")
@Tag(name = "客户端压缩包")
public class ClientPackageController {

    private final ClientPackageService clientPackageService;

    public ClientPackageController(ClientPackageService clientPackageService) {
        this.clientPackageService = clientPackageService;
    }

    @Operation(summary = "压缩包元数据（登录可读；未上传时 data 为 null）")
    @GetMapping
    public ApiResponse<ClientPackageDto> meta(@PathVariable Long roomId) {
        return ApiResponse.ok(clientPackageService.meta(roomId));
    }

    @Operation(summary = "上传 / 替换压缩包（仅房主；大小受等级上限限制）")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ClientPackageDto> upload(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long roomId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(clientPackageService.upload(accountId, roomId, file));
    }

    @Operation(summary = "删除压缩包（仅房主）")
    @DeleteMapping
    public ApiResponse<Void> delete(
            @RequestAttribute(AuthInterceptor.ATTR_ACCOUNT_ID) Long accountId,
            @PathVariable Long roomId) {
        clientPackageService.delete(accountId, roomId);
        return ApiResponse.ok(null);
    }

    @Operation(summary = "下载压缩包（登录即可，供玩家在「启动游戏」时获取）")
    @GetMapping("/file")
    public ResponseEntity<Resource> download(@PathVariable Long roomId) {
        ClientPackageFile file = clientPackageService.load(roomId);
        String disposition = ContentDisposition.attachment()
                .filename(file.fileName(), StandardCharsets.UTF_8)
                .build()
                .toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.sizeBytes())
                .body(new FileSystemResource(file.path()));
    }
}