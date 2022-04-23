package com.github.tth05.scnet.message.impl;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.IMessageBus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;

/**
 * Default implementation of {@link IMessageBus}
 */
public class DefaultMessageBus implements IMessageBus {

    /**
     * A map containing the registered listeners for each event
     */
    @NotNull
    private final Map<Class<?>, List<RegisteredListener>> listeners = new HashMap<>();

    @Override
    public <T extends AbstractMessage> void listenAlways(@NotNull Class<T> messageClass, @NotNull Consumer<T> listener) {
        listenAlways(messageClass, null, listener);
    }

    @Override
    public <T extends AbstractMessage> void listenAlways(@NotNull Class<T> messageClass, @Nullable Object associatedObject, @NotNull Consumer<T> listener) {
        synchronized (this.listeners) {
            this.listeners.computeIfAbsent(messageClass, (c) -> new ArrayList<>()).add(new RegisteredListener(false, listener, associatedObject));
        }
    }

    @Override
    public <T extends AbstractMessage> void listenOnce(@NotNull Class<T> messageClass, @NotNull Consumer<T> listener) {
        listenOnce(messageClass, null, listener);
    }

    @Override
    public <T extends AbstractMessage> void listenOnce(@NotNull Class<T> messageClass, @Nullable Object associatedObject, @NotNull Consumer<T> listener) {
        synchronized (this.listeners) {
            this.listeners.computeIfAbsent(messageClass, (c) -> new ArrayList<>()).add(new RegisteredListener(true, listener, associatedObject));
        }
    }

    @Override
    public <T extends AbstractMessage> void unregister(@NotNull Class<T> messageClass, @NotNull Consumer<T> listener) {
        synchronized (this.listeners) {
            List<RegisteredListener> registeredListeners = this.listeners.get(messageClass);
            if (registeredListeners == null)
                return;

            registeredListeners.removeIf(rl -> rl.listener == listener);
        }
    }

    @Override
    public <T extends AbstractMessage> void unregister(@NotNull Class<T> messageClass, @Nullable Object associatedObject) {
        if (associatedObject == null)
            return;

        synchronized (this.listeners) {
            List<RegisteredListener> registeredListeners = this.listeners.get(messageClass);
            if (registeredListeners == null)
                return;

            registeredListeners.removeIf(rl -> rl.associatedObject == associatedObject);
        }
    }

    @Override
    public void post(@NotNull AbstractMessage message) {
        synchronized (this.listeners) {
            for (Iterator<RegisteredListener> iterator = this.listeners.getOrDefault(message.getClass(), Collections.emptyList()).iterator(); iterator.hasNext(); ) {
                RegisteredListener listener = iterator.next();

                try {
                    //noinspection unchecked
                    listener.listener.accept(message);
                } catch (Throwable t) {
                    System.err.println("Exception occurred while handling message: " + message.getClass().getName());
                    t.printStackTrace();
                }

                if (listener.once)
                    iterator.remove();
            }
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
        @Nullable
        private final Object associatedObject;

        private RegisteredListener(boolean once, @NotNull Consumer<?> listener, @Nullable Object associatedObject) {
            this.once = once;
            this.listener = listener;
            this.associatedObject = associatedObject;
        }
    }
}
