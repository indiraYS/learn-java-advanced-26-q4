package indira.yessimova.learn.model;

import jakarta.annotation.Nonnull;

public record Customer(@Nonnull Long id, @Nonnull String name, @Nonnull String email) {
}
