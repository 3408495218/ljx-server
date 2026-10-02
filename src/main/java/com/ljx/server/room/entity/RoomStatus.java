package com.ljx.server.room.entity;

/** 房间在线态；由房主心跳维持，超时由定时任务置为 OFFLINE */
public enum RoomStatus {
    ONLINE,
    OFFLINE
}