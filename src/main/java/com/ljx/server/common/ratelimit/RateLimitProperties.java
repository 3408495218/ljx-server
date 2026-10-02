package com.ljx.server.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 按 IP 的接口限流规则；字段初始值即缺省值，配置缺失时仍保持开启 */
@ConfigurationProperties("ljx.rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    private Rule login = new Rule(10, Duration.ofMinutes(5));

    private Rule register = new Rule(5, Duration.ofMinutes(10));

    private Rule refresh = new Rule(30, Duration.ofMinutes(5));

    private Rule emailCode = new Rule(5, Duration.ofMinutes(10));

    // ---------- 业务写接口（P1 修复：原先只有登录类接口限流，建房/购买/兑换可被刷） ----------

    /** 房间写操作：建房、进入/离开房间、心跳、上传客户端包 */
    private Rule roomWrite = new Rule(60, Duration.ofMinutes(10));

    /** 购买（道具与 VIP 档位） */
    private Rule purchase = new Rule(30, Duration.ofMinutes(10));

    /** CDK 兑换（防止被脚本枚举） */
    private Rule redeem = new Rule(20, Duration.ofMinutes(10));

    /** 固定窗口：window 内最多 limit 次 */
    public record Rule(int limit, Duration window) {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Rule getLogin() {
        return login;
    }

    public void setLogin(Rule login) {
        this.login = login;
    }

    public Rule getRegister() {
        return register;
    }

    public void setRegister(Rule register) {
        this.register = register;
    }

    public Rule getRefresh() {
        return refresh;
    }

    public void setRefresh(Rule refresh) {
        this.refresh = refresh;
    }

    public Rule getEmailCode() {
        return emailCode;
    }

    public void setEmailCode(Rule emailCode) {
        this.emailCode = emailCode;
    }

    public Rule getRoomWrite() {
        return roomWrite;
    }

    public void setRoomWrite(Rule roomWrite) {
        this.roomWrite = roomWrite;
    }

    public Rule getPurchase() {
        return purchase;
    }

    public void setPurchase(Rule purchase) {
        this.purchase = purchase;
    }

    public Rule getRedeem() {
        return redeem;
    }

    public void setRedeem(Rule redeem) {
        this.redeem = redeem;
    }
}