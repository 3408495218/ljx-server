package com.ljx.server.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 后台页面入口。
 * <p>
 * Spring Boot 的欢迎页机制（自动把目录请求映射到 index.html）**只作用于根路径**，
 * 子目录 {@code /admin/} 不会自动落到 {@code /admin/index.html}，直接访问会抛
 * {@code NoResourceFoundException}（实测 500）。这里显式转发一次。
 */
@Controller
public class AdminPageController {

    @GetMapping({"/admin", "/admin/"})
    public String index() {
        return "forward:/admin/index.html";
    }
}
