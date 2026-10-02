package com.ljx.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 与请求来源相关的安全开关 */
@ConfigurationProperties("ljx.security")
public class SecurityProperties {

    /**
     * 是否信任 X-Forwarded-For。
     * 默认 false：直连部署下该头可被客户端伪造，据此限流等于把限流交给攻击者。
     * 仅当服务确实位于可信反向代理（Nginx 等会覆写该头）之后才置 true。
     */
    private boolean trustForwardedHeader = false;

    public boolean isTrustForwardedHeader() {
        return trustForwardedHeader;
    }

    public void setTrustForwardedHeader(boolean trustForwardedHeader) {
        this.trustForwardedHeader = trustForwardedHeader;
    }
}