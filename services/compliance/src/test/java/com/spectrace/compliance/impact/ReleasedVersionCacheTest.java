package com.spectrace.compliance.impact;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ReleasedVersionCacheTest {
    @Test void boundedCacheSeparatesTenantsAndDoesNotCacheMisses() {
        var cache = new ReleasedVersionCache(2);
        assertThat(cache.get("a:id", () -> "A")).isEqualTo("A");
        assertThat(cache.get("b:id", () -> "B")).isEqualTo("B");
        assertThat(cache.get("a:id", () -> "wrong")).isEqualTo("A");
        cache.get("c:id", () -> "C");
        assertThat(cache.size()).isEqualTo(2);
        assertThat(cache.get("b:id", () -> "reload")).isEqualTo("reload");
        assertThatThrownBy(() -> cache.get("missing", () -> { throw new IllegalStateException(); })).isInstanceOf(IllegalStateException.class);
        assertThat(cache.get("missing", () -> "later")).isEqualTo("later");
    }

    @Test void rollbackCannotPublishUncommittedReleasedVersions() {
        var cache = new ReleasedVersionCache(2);
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.get("rolled-back", () -> "uncommitted");
            assertThat(cache.size()).isZero();
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(1));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        assertThat(cache.get("rolled-back", () -> "committed")).isEqualTo("committed");
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.get("next", () -> "good");
            assertThat(cache.size()).isEqualTo(1);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        assertThat(cache.get("next", () -> "wrong")).isEqualTo("good");
    }
}
