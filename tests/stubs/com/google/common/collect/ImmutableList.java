package com.google.common.collect;

import java.util.ArrayList;
import java.util.Collection;

public class ImmutableList<E> extends ArrayList<E> {
    public ImmutableList() {
        super();
    }

    public ImmutableList(Collection<? extends E> c) {
        super(c != null ? c : java.util.Collections.emptyList());
    }

    public static <E> ImmutableList<E> of() {
        return new ImmutableList<E>();
    }

    public static <E> ImmutableList<E> of(E element) {
        ImmutableList<E> list = new ImmutableList<E>();
        list.add(element);
        return list;
    }

    public static <E> ImmutableList<E> copyOf(Collection<? extends E> elements) {
        return new ImmutableList<E>(elements);
    }
}
