package dev.challenge.activation.api;

import dev.challenge.activation.domain.shared.ResultPage;

import java.util.List;
import java.util.function.Function;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    static <S, T> PageResponse<T> from(ResultPage<S> source, Function<? super S, ? extends T> mapper) {
        ResultPage<T> page = source.map(mapper);
        return new PageResponse<>(page.items(), page.page(), page.size(), page.totalItems(), page.totalPages());
    }
}
