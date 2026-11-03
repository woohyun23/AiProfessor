package com.aiprofessor.backend.support.web;

import java.util.List;

public record PageResponse<T>(
        boolean success,
        String message,
        List<T> data,
        PageInfo page
) {

    public PageResponse {
        data = data == null ? List.of() : List.copyOf(data);
    }

    public static <T> PageResponse<T> success(
            String message,
            List<T> data,
            String nextCursor,
            boolean hasNext
    ) {
        List<T> safeData = data == null
                ? List.of()
                : List.copyOf(data);

        PageInfo pageInfo = new PageInfo(
                nextCursor,
                hasNext,
                safeData.size()
        );

        return new PageResponse<> (
                true,
                message,
                safeData,
                pageInfo
        );
    }

    public record PageInfo(
            String nextCursor,
            boolean hasNext,
            int size
    ) {
    }
}
