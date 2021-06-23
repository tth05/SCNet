package com.github.tth05.scnet.message;

import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * A message bus is responsible for receiving messages from a {@link IMessageProcessor} and distributing them to
 * listeners.
 */
public interface IMessageBus {

    /**
     * Registers a given {@code listener} with this message bus to receive events for the given {@code messageClass}.
     *
     * @param messageClass the class of the message to receive events for
     * @param listener     the listener
     */
    <T extends AbstractMessage> void listenAlways(@NotNull Class<T> messageClass, @NotNull Consumer<T> listener);

    /**
     * Behaves like {@link #listenAlways(Class, Consumer)}, but the {@code listener} will unregistered after receiving a
     * single message.
     */
    <T extends AbstractMessage> void listenOnce(@NotNull Class<T> messageClass, @NotNull Consumer<T> listener);

    /**
     * Posts a message to this bus and distributes it to all listeners. Should be called by a {@link IMessageProcessor}.
     *
     * @param message the message
     */
    void post(@NotNull AbstractMessage message);
}
