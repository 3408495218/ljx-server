package com.ljx.server.common.net;

import jakarta.servlet.http.HttpServletRequest;

/** 客户端 IP 解析：限流与审计共用，保证两处口径一致 */
public final class ClientIp {

    private ClientIp() {
    }

    public static String resolve(HttpServletRequest request, boolean trustForwardedHeader) {
        if (trustForwardedHeader) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
                if (!first.isEmpty()) {
                    return first;
                }
            }
        }
        return request.getRemoteAddr();
    }
}