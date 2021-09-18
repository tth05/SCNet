package com.github.tth05.scnet.message.impl;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.IMessageBus;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Default implementation of {@link IMessageBus}
 */
public class DefaultMessageBus implements IMessageBus {

    /**
     * A map containing the registered listeners for each event
     */
    @NotNull
    private final Map<Class<?>, List<RegisteredListener>> listeners = new ConcurrentHashMap<>();

    @Override
    public <T extends AbstractMessage> void listenAlways(@NotNull Class<T> messageClass, @NotNull Consumer<T> listener) {
        this.listeners.computeIfAbsent(messageClass, (c) -> new ArrayList<>()).add(new RegisteredListener(false, listener));
    }

    @Override
    public <T extends AbstractMessage> void listenOnce(@NotNull Class<T> messageClass, @NotNull Consumer<T> listener) {
        this.listeners.computeIfAbsent(messageClass, (c) -> new ArrayList<>()).add(new RegisteredListener(true, listener));
    }

    @Override
    public <T extends AbstractMessage> void unregister(@NotNull Class<T> messageClass, @NotNull Consumer<T> listener) {
        List<RegisteredListener> registeredListeners = this.listeners.get(messageClass);
        if (registeredListeners == null)
            return;

        registeredListeners.removeIf(rl -> rl.listener == listener);
    }

    @Override
    public void post(@NotNull AbstractMessage message) {
        for (Iterator<RegisteredListener> iterator = this.listeners.getOrDefault(message.getClass(), Collections.emptyList()).iterator(); iterator.hasNext(); ) {
            RegisteredListener listener = iterator.next();

            //noinspection unchecked
            listener.listener.accept(message);
            if (listener.once)
                iterator.remove();
        }
    }

    /**
     * Wrapper class for registered listeners which stores the listener itself and whether the listener should
     * only receive a single event or multiple
     */
    private static final class RegisteredListener {

        private final boolean once;
        @NotNull
        private final Consumer listener;

        private RegisteredListener(boolean once, @NotNull Consumer<?> listener) {
            this.once = once;
            this.listener = listener;
        }
    }
}
