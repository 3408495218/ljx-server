package com.ljx.server.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 大厅状态推送：客户端连 ws://host/ws/lobby（原生 WebSocket，STOMP 帧），订阅 /topic/lobby。
 * 大厅是公开只读数据，握手不做 JWT 校验；写操作仍全部走 /api/** 的 Bearer 认证。
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    /** 当前大厅推送连接数（每次变动记一行，便于确认客户端推送通道是否真的建立） */
    private final AtomicInteger sessions = new AtomicInteger();

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/lobby")
                .setAllowedOriginPatterns(
                        "tauri://localhost",
                        "http://tauri.localhost",
                        "http://localhost:5173");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
    }

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        log.info("大厅推送连接建立：当前 {} 个", sessions.incrementAndGet());
    }

    @EventListener
    public void onDisconnected(SessionDisconnectEvent event) {
        log.info("大厅推送连接断开：当前 {} 个", Math.max(0, sessions.decrementAndGet()));
    }
}