package com.google.common.collect;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

public abstract class ImmutableSet<E> implements Set<E>, Serializable {

    private static final ImmutableSet<Object> EMPTY = new RegularImmutableSet<>(Collections.emptySet());

    ImmutableSet() {
    }

    @SuppressWarnings("unchecked")
    public static <E> ImmutableSet<E> of() {
        return (ImmutableSet<E>) EMPTY;
    }

    public static <E> ImmutableSet<E> of(E e1) {
        return new RegularImmutableSet<>(Collections.singleton(e1));
    }

    public static <E> ImmutableSet<E> of(E e1, E e2) {
        Set<E> set = new LinkedHashSet<>(4);
        set.add(e1);
        set.add(e2);
        return new RegularImmutableSet<>(Collections.unmodifiableSet(set));
    }

    public static <E> ImmutableSet<E> of(E e1, E e2, E e3) {
        Set<E> set = new LinkedHashSet<>(4);
        set.add(e1);
        set.add(e2);
        set.add(e3);
        return new RegularImmutableSet<>(Collections.unmodifiableSet(set));
    }

    public static <E> ImmutableSet<E> of(E e1, E e2, E e3, E e4) {
        Set<E> set = new LinkedHashSet<>(8);
        set.add(e1);
        set.add(e2);
        set.add(e3);
        set.add(e4);
        return new RegularImmutableSet<>(Collections.unmodifiableSet(set));
    }

    public static <E> ImmutableSet<E> of(E e1, E e2, E e3, E e4, E e5) {
        Set<E> set = new LinkedHashSet<>(8);
        set.add(e1);
        set.add(e2);
        set.add(e3);
        set.add(e4);
        set.add(e5);
        return new RegularImmutableSet<>(Collections.unmodifiableSet(set));
    }

    @SafeVarargs
    public static <E> ImmutableSet<E> of(E e1, E e2, E e3, E e4, E e5, E e6, E... others) {
        Set<E> set = new LinkedHashSet<>(8 + (others != null ? others.length : 0));
        set.add(e1);
        set.add(e2);
        set.add(e3);
        set.add(e4);
        set.add(e5);
        set.add(e6);
        if (others != null) {
            set.addAll(Arrays.asList(others));
        }
        return new RegularImmutableSet<>(Collections.unmodifiableSet(set));
    }

    public static <E> ImmutableSet<E> copyOf(Collection<? extends E> elements) {
        if (elements instanceof ImmutableSet) {
            @SuppressWarnings("unchecked")
            ImmutableSet<E> result = (ImmutableSet<E>) elements;
            return result;
        }
        if (elements == null || elements.isEmpty()) {
            return of();
        }
        return new RegularImmutableSet<>(Collections.unmodifiableSet(new LinkedHashSet<>(elements)));
    }

    public static <E> ImmutableSet<E> copyOf(E[] elements) {
        if (elements == null || elements.length == 0) {
            return of();
        }
        return new RegularImmutableSet<>(Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(elements))));
    }

    public static <E> Builder<E> builder() {
        return new Builder<>();
    }

    public static class Builder<E> {
        private final Set<E> contents = new LinkedHashSet<>();

        public Builder<E> add(E element) {
            contents.add(element);
            return this;
        }

        @SafeVarargs
        public final Builder<E> add(E... elements) {
            if (elements != null) {
                contents.addAll(Arrays.asList(elements));
            }
            return this;
        }

        public Builder<E> addAll(Iterable<? extends E> elements) {
            if (elements != null) {
                for (E e : elements) {
                    contents.add(e);
                }
            }
            return this;
        }

        public ImmutableSet<E> build() {
            if (contents.isEmpty()) {
                return of();
            }
            return new RegularImmutableSet<>(Collections.unmodifiableSet(new LinkedHashSet<>(contents)));
        }
    }

    private static final class RegularImmutableSet<E> extends ImmutableSet<E> {
        private final Set<E> delegate;

        RegularImmutableSet(Set<E> delegate) {
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
        public boolean contains(Object o) {
            return delegate.contains(o);
        }

        @Override
        public Iterator<E> iterator() {
            return delegate.iterator();
        }

        @Override
        public Object[] toArray() {
            return delegate.toArray();
        }

        @Override
        public <T> T[] toArray(T[] a) {
            return delegate.toArray(a);
        }

        @Override
        public boolean add(E e) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return delegate.containsAll(c);
        }

        @Override
        public boolean addAll(Collection<? extends E> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean retainAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeAll(Collection<?> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
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
