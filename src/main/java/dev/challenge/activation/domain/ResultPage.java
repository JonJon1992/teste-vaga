package dev.challenge.activation.domain;

import java.util.List;
import java.util.function.Function;

public record ResultPage<T>(List<T> items, int page, int size, long totalItems) {

    public ResultPage {
        items = List.copyOf(items);
    }

    public int totalPages() {
        return (int) ((totalItems + size - 1) / size);
    }

    public <R> ResultPage<R> map(Function<? super T, ? extends R> mapper) {
        List<R> mapped = items.stream().<R>map(mapper).toList();
        return new ResultPage<>(mapped, page, size, totalItems);
    }
}
