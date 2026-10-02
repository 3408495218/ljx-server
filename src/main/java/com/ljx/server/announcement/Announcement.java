package com.ljx.server.announcement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 公告：玩家端**底部状态栏右侧**轮播展示的那条文案（该位置原本固定显示"问题反馈，请加官方QQ群"）。
 * 无启用中的公告时，前端回退显示原来的 QQ 群文案，不会出现空栏。
 */
@Entity
@Table(name = "announcement")
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    /** 越小越靠前 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected Announcement() {
    }

    public Announcement(String content, int sortOrder, LocalDateTime at) {
        this.content = content;
        this.sortOrder = sortOrder;
        this.enabled = true;
        this.createdAt = at;
        this.updatedAt = at;
    }

    /** 局部更新：null 表示该字段不变（与项目其他 UpdateRequest 的口径一致） */
    public void update(String content, Integer sortOrder, Boolean enabled, LocalDateTime at) {
        if (content != null) {
            this.content = content;
        }
        if (sortOrder != null) {
            this.sortOrder = sortOrder;
        }
        if (enabled != null) {
            this.enabled = enabled;
        }
        this.updatedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
