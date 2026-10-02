package com.ljx.server.content.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 房间内容类型，对应房主本机的两个目录：{@code plugins/} 与 {@code mods/}。
 * 存库用 {@code @Enumerated(STRING)} 的大写名，对外 JSON 用小写 slug。
 */
public enum ContentType {

    PLUGIN("plugin"),
    MOD("mod");

    private final String slug;

    ContentType(String slug) {
        this.slug = slug;
    }

    @JsonValue
    public String slug() {
        return slug;
    }

    @JsonCreator
    public static ContentType fromSlug(String slug) {
        for (ContentType type : values()) {
            if (type.slug.equals(slug)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知内容类型：" + slug);
    }
}