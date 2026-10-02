package com.ljx.server.ws;

/**
 * 大厅广播事件：房间上线 / 下线 / 人数变化，以及**公告变更**。
 * <p>
 * 只带大厅卡片需要的字段，避免客户端收到事件后再回查详情。
 * 公告事件（{@link Type#ANNOUNCEMENT}）只发一个"变了"的信号、不带内容
 * （公告是低频数据，客户端收到后自己重拉即可），此时 roomId 等字段为 null。
 */
public record LobbyEvent(Type type,
                         Long roomId,
                         String name,
                         int players,
                         int capacity,
                         boolean online,
                         String core,
                         String mcVersion,
                         String mode) {

    public enum Type {
        ONLINE,
        OFFLINE,
        PLAYERS,
        /** 公告有变化：客户端收到后重新拉一次公告（其余字段为 null / 默认值） */
        ANNOUNCEMENT
    }
}