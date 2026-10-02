package com.ljx.server.social.dto;

public final class SocialDtos {

    private SocialDtos() {
    }

    /** 收藏操作结果：便于前端一次刷新按钮态与计数 */
    public record FavoriteStateDto(boolean favorited, long favoriteCount) {
    }
}