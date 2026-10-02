package com.ljx.server.level;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 等级配置（后台可改）：从本级升到下一级需要多少经验、该等级允许的客户端压缩包上传上限。
 * <p>
 * {@code expToNext <= 0} 表示已封顶，不再升级。
 */
@Entity
@Table(name = "level_config")
public class LevelConfig {

    @Id
    private Integer level;

    @Column(name = "exp_to_next", nullable = false)
    private int expToNext;

    @Column(name = "upload_mb", nullable = false)
    private int uploadMb;

    @Column(name = "note", length = 64)
    private String note;

    protected LevelConfig() {
    }

    /** 后台新增等级档用 */
    public static LevelConfig create(int level, int expToNext, int uploadMb, String note) {
        LevelConfig config = new LevelConfig();
        config.level = level;
        config.expToNext = expToNext;
        config.uploadMb = uploadMb;
        config.note = note == null || note.isBlank() ? null : note;
        return config;
    }

    /** 是否封顶等级（不再升级） */
    public boolean isTop() {
        return expToNext <= 0;
    }

    /** 后台修改（null 表示该字段不变） */
    public void update(Integer expToNext, Integer uploadMb, String note) {
        if (expToNext != null) {
            this.expToNext = expToNext;
        }
        if (uploadMb != null) {
            this.uploadMb = uploadMb;
        }
        if (note != null) {
            this.note = note.isBlank() ? null : note;
        }
    }

    public Integer getLevel() {
        return level;
    }

    public int getExpToNext() {
        return expToNext;
    }

    public int getUploadMb() {
        return uploadMb;
    }

    public String getNote() {
        return note;
    }
}
