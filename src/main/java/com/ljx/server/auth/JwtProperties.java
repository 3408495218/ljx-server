package com.ljx.server.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ljx.jwt")
public record JwtProperties(String secret, Duration accessTtl, Duration refreshTtl) {
}
