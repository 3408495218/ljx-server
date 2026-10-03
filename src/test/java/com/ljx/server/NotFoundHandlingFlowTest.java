package com.ljx.server;

import com.ljx.server.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 不存在的路径必须返回 **404**，不能落进 {@code @ExceptionHandler(Exception.class)} 的兜底分支
 * 变成 5000「服务器内部错误」。
 * <p>
 * 这条回归很重要，因为它同时影响三件事：
 * <ol>
 *   <li>被关闭的接口文档（{@code SPRINGDOC_*_ENABLED=false}）暴露成 404 后不该报 500；</li>
 *   <li>浏览器自动请求的 {@code /favicon.ico}、扫描器探测的任意路径，此前每条都写一整段
 *       ERROR 堆栈 —— 在高负载机器上属于真实开销；</li>
 *   <li>桌面端把 5000 当作"服务器故障"，会掩盖"服务器地址填错"这个真正原因。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotFoundHandlingFlowTest extends GlobalConfigGuardTest {

    private static final ParameterizedTypeReference<ApiResponse<Void>> VOID =
            new ParameterizedTypeReference<>() {
            };

    @Autowired
    private TestRestTemplate rest;

    private ResponseEntity<ApiResponse<Void>> get(String path) {
        return rest.exchange(path, HttpMethod.GET, null, VOID);
    }

    @Test
    void unknownPathReturnsNotFoundNotServerError() {
        var res = get("/no/such/path");

        assertThat(res.getStatusCode())
                .as("未知路径应为 404；若为 500 说明被 Exception 兜底处理器吞掉了")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code())
                .as("业务码应为 1002（资源不存在），而不是 5000（服务器内部错误）")
                .isEqualTo(1002);
    }

    @Test
    void missingStaticAssetReturnsNotFound() {
        // 浏览器默认会请求 favicon，此前每条都会在日志里留一段 ERROR 堆栈
        var res = get("/favicon.ico");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isEqualTo(1002);
    }

    @Test
    void realApiStillWorks() {
        // 加 404 处理器不能影响正常业务接口
        var res = rest.exchange("/api/rooms?view=all", HttpMethod.GET, null,
                new ParameterizedTypeReference<ApiResponse<Object>>() {
                });

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().code()).isZero();
    }
}
