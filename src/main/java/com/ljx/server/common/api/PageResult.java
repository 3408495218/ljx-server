package com.ljx.server.common.api;

import java.util.List;

/** 分页结果；page 从 1 开始，total 为满足筛选条件的总条数 */
public record PageResult<T>(List<T> items, int page, long total) {
}