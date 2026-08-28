package com.fitpilot.common.response;

import java.util.List;

public record PageResult<T>(List<T> items, long total, long page, long size, long pages) {
    public static <T> PageResult<T> of(List<T> items, long total, long page, long size) {
        return new PageResult<>(items, total, page, size, size == 0 ? 0 : (total + size - 1) / size);
    }
}
