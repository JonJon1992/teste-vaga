package dev.challenge.activation.domain.shared;

public final class Guard {

    private Guard() {
    }

    public static String notBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " é obrigatório");
        }
        return value;
    }
}
