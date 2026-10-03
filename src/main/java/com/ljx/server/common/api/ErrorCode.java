package com.ljx.server.common.api;

public enum ErrorCode {

    VALIDATION_FAILED(1001, "参数错误"),
    NOT_FOUND(1002, "请求的资源不存在"),

    USERNAME_TAKEN(1101, "用户名已被占用"),
    BAD_CREDENTIALS(1102, "用户名或密码错误"),
    REFRESH_TOKEN_INVALID(1103, "登录状态已失效，请重新登录"),
    UNAUTHENTICATED(1104, "请先登录"),

    BAD_EMAIL(1201, "邮箱格式错误"),
    BAD_EMAIL_CODE(1202, "验证码错误或已过期"),
    MAIL_TOO_FREQUENT(1203, "验证码发送过于频繁，请稍后再试"),
    MAIL_SEND_FAILED(1204, "验证码发送失败，请稍后再试"),

    ROOM_NOT_FOUND(1301, "房间不存在"),
    ROOM_FORBIDDEN(1302, "无权操作该房间"),
    ROOM_LOCKED(1303, "房间已上锁，暂不对外开放"),
    ROOM_OFFLINE(1304, "房间当前未在线"),
    ROOM_EMAIL_REQUIRED(1305, "该房间要求绑定邮箱后加入"),
    EMAIL_REQUIRED_FOR_CREATE(1306, "创建房间需先绑定邮箱"),
    ROOM_NO_GUEST(1307, "该房间禁止游客进入，请先登录账号"),

    QUOTA_EXCEEDED(1401, "超出配额上限"),

    CONTENT_NOT_FOUND(1501, "内容不存在"),
    CDK_NOT_FOUND(1502, "兑换码不存在"),
    CDK_DISABLED(1503, "兑换码已停用"),
    CDK_EXPIRED(1504, "兑换码已过期"),
    CDK_USED_UP(1505, "兑换码已被领完"),
    CDK_ALREADY_REDEEMED(1506, "该兑换码本账号已领取过"),
    CDK_REDEEMED_CANNOT_DELETE(1508, "该兑换码已有领取记录，只能停用不能删除"),
    SHOP_ITEM_NOT_FOUND(1509, "商品不存在或已下架"),
    INSUFFICIENT_COINS(1510, "钻石不足，无法购买"),
    ROOM_REQUIRED_FOR_ITEM(1511, "该商品需要指定作用的房间"),
    NOT_ROOM_OWNER(1512, "只能对自己名下的房间使用"),
    VIP_PLAN_NOT_FOUND(1514, "VIP 档位不存在"),
    LEVEL_CONFIG_NOT_FOUND(1515, "等级配置不存在"),
    LEVEL_CONFIG_EXISTS(1516, "该等级配置已存在"),

    RATE_LIMITED(1601, "操作过于频繁，请稍后再试"),
    ADMIN_PASSWORD_CHANGE_REQUIRED(1604, "请先修改管理员密码"),
    ADMIN_FORBIDDEN(1602, "该来源不允许访问管理后台"),
    ADMIN_NOT_CONFIGURED(1603, "未配置管理员账号，请设置 LJX_ADMIN_USERNAME / LJX_ADMIN_PASSWORD 后重启"),
    ANNOUNCEMENT_NOT_FOUND(1507, "公告不存在"),

    INTERNAL_ERROR(5000, "服务器内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
