package com.google.common.collect;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public abstract class ImmutableMap<K, V> implements Map<K, V>, Serializable {

    private static final ImmutableMap<Object, Object> EMPTY = new RegularImmutableMap<>(Collections.emptyMap());

    ImmutableMap() {
    }

    @SuppressWarnings("unchecked")
    public static <K, V> ImmutableMap<K, V> of() {
        return (ImmutableMap<K, V>) EMPTY;
    }

    public static <K, V> ImmutableMap<K, V> of(K k1, V v1) {
        return new RegularImmutableMap<>(Collections.singletonMap(k1, v1));
    }

    public static <K, V> ImmutableMap<K, V> copyOf(Map<? extends K, ? extends V> map) {
        if (map instanceof ImmutableMap) {
            @SuppressWarnings("unchecked")
            ImmutableMap<K, V> result = (ImmutableMap<K, V>) map;
            return result;
        }
        if (map == null || map.isEmpty()) {
            return of();
        }
        return new RegularImmutableMap<>(Collections.unmodifiableMap(new LinkedHashMap<>(map)));
    }

    public static <K, V> Builder<K, V> builder() {
        return new Builder<>();
    }

    public static class Builder<K, V> {
        private final Map<K, V> entries = new LinkedHashMap<>();

        public Builder() {
        }

        public Builder<K, V> put(K key, V value) {
            entries.put(key, value);
            return this;
        }

        public Builder<K, V> put(Entry<? extends K, ? extends V> entry) {
            if (entry != null) {
                entries.put(entry.getKey(), entry.getValue());
            }
            return this;
        }

        public Builder<K, V> putAll(Map<? extends K, ? extends V> map) {
            if (map != null) {
                entries.putAll(map);
            }
            return this;
        }

        public ImmutableMap<K, V> build() {
            return buildKeepingLast();
        }

        public ImmutableMap<K, V> buildOrThrow() {
            return buildKeepingLast();
        }

        public ImmutableMap<K, V> buildKeepingLast() {
            if (entries.isEmpty()) {
                return of();
            }
            return new RegularImmutableMap<>(Collections.unmodifiableMap(new LinkedHashMap<>(entries)));
        }
    }

    private static final class RegularImmutableMap<K, V> extends ImmutableMap<K, V> {
        private final Map<K, V> delegate;

        RegularImmutableMap(Map<K, V> delegate) {
            this.delegate = delegate;
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean isEmpty() {
            return delegate.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return delegate.containsKey(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return delegate.containsValue(value);
        }

        @Override
        public V get(Object key) {
            return delegate.get(key);
        }

        @Override
        public V put(K key, V value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public V remove(Object key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> m) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<K> keySet() {
            return delegate.keySet();
        }

        @Override
        public Collection<V> values() {
            return delegate.values();
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            return delegate.entrySet();
        }

        @Override
        public boolean equals(Object o) {
            return delegate.equals(o);
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
