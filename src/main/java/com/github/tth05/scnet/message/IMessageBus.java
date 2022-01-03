package com.github.tth05.scnet.message;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * A message bus is responsible for receiving messages from a {@link IMessageProcessor} and distributing them to
 * listeners. All methods in this class should be thread-safe.
 */
public interface IMessageBus {

    /**
     * @see #listenAlways(Class, Object, Consumer)
     */
    <T extends AbstractMessage> void listenAlways(@NotNull Class<T> messageClass, @NotNull Consumer<T> listener);

    /**
     * @see #listenOnce(Class, Object, Consumer)
     */
    <T extends AbstractMessage> void listenOnce(@NotNull Class<T> messageClass, @NotNull Consumer<T> listener);

    /**
     * Registers a given {@code listener} with this message bus to receive events for the given {@code messageClass}.
     *
     * @param messageClass     The class of the message to receive events for
     * @param associatedObject An object which will be associated with the given listener, such that the same object can
     *                         later be used to call {@link #unregister(Class, Object)}
     * @param listener         The listener
     */
    <T extends AbstractMessage> void listenAlways(@NotNull Class<T> messageClass, @Nullable Object associatedObject, @NotNull Consumer<T> listener);

    /**
     * Behaves like {@link #listenAlways(Class, Consumer)}, but the {@code listener} will unregistered after receiving a
     * single message.
     */
    <T extends AbstractMessage> void listenOnce(@NotNull Class<T> messageClass, @Nullable Object associatedObject, @NotNull Consumer<T> listener);

    /**
     * Unregisters the given listener
     */
    <T extends AbstractMessage> void unregister(@NotNull Class<T> messageClass, @NotNull Consumer<T> listener);

    /**
     * Unregisters the given listener
     *
     * @param associatedObject The object which was used to register some listener. If this is {@code null}, this method
     *                         will do nothing
     */
    <T extends AbstractMessage> void unregister(@NotNull Class<T> messageClass, @Nullable Object associatedObject);

    /**
     * Posts a message to this bus and distributes it to all listeners. Should be called by a {@link IMessageProcessor}.
     *
     * @param message The message to post
     */
    void post(@NotNull AbstractMessage message);
}
