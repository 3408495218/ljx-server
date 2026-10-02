package com.ljx.server.room;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** 成员存在性超时：每 15 秒扫描，超过 90 秒未续期视为已离开（与房间心跳下线同口径） */
@Component
public class MemberScheduler {

    private static final Duration TIMEOUT = Duration.ofSeconds(90);

    private final MemberService memberService;

    public MemberScheduler(MemberService memberService) {
        this.memberService = memberService;
    }

    @Scheduled(fixedDelay = 15_000L, initialDelay = 20_000L)
    public void scanStaleMembers() {
        memberService.expireStale(TIMEOUT);
    }
}
