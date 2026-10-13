package com.spectrace.compliance.impact;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded per-replica cache. Only immutable, successfully committed version snapshots enter it. */
@Component
public class ReleasedVersionCache {
    private final int maximum;
    private final Map<String, Object> entries = new LinkedHashMap<>(128, 0.75f, true);

    public ReleasedVersionCache(@Value("${spectrace.compliance.cache.maximum-entries:20000}") int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("Cache maximum must be positive");
        this.maximum = maximum;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> loader) {
        synchronized (entries) {
            Object existing = entries.get(key);
            if (existing != null) return (T) existing;
        }
        T loaded = java.util.Objects.requireNonNull(loader.get(), "Missing versions cannot be cached");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { retain(key, loaded); }
            });
        } else {
            retain(key, loaded);
        }
        return loaded;
    }

    private void retain(String key, Object value) {
        synchronized (entries) {
            entries.putIfAbsent(key, value);
            while (entries.size() > maximum) entries.remove(entries.keySet().iterator().next());
        }
    }

    public int size() { synchronized (entries) { return entries.size(); } }
}
