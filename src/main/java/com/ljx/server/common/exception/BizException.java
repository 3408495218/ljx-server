package com.ljx.server.common.exception;

import com.ljx.server.common.api.ErrorCode;

public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.code = errorCode.code();
    }

    /** 需要携带具体数值（如配额上限）时使用 */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.code();
    }

    public int code() {
        return code;
    }
}
