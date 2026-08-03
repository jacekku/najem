package pl.najem.pm.domain;

import java.util.ArrayList;
import java.util.List;

/** Soft checks. The PM domain has exactly one hard invariant; everything else warns. */
public final class Warnings {

    private final List<String> messages = new ArrayList<>();

    public Warnings add(String message) {
        messages.add(message);
        return this;
    }

    public List<String> messages() {
        return List.copyOf(messages);
    }

    public boolean isEmpty() {
        return messages.isEmpty();
    }
}
