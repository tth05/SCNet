package com.github.tth05.scnet.message;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * A message processor will send enqueued messages and forward received messages to a {@link IMessageBus}.
 * <br>
 * To recognize messages, they must be registered with the message processor. Outgoing-only messages only need their
 * class. Incoming and bidirectional messages also need a caller-provided factory.
 */
public interface IMessageProcessor {

    /**
     * Registers an outgoing-only message with this message processor.
     * <br>
     * The id may be anything greater than 0. 0 is reserved for {@link com.github.tth05.scnet.message.impl.EmptyMessage}
     *
     * @param id           the id for the message, has to be unique amongst all other messages
     * @param messageClass the class of the message
     * @throws IllegalArgumentException if the given {@code id} is smaller than 1 or there already is a message
     *                                  registered with the given id.
     */
    <T extends AbstractMessageOutgoing> void registerMessage(short id, @NotNull Class<T> messageClass);

    /**
     * Registers an incoming or bidirectional message using a caller-provided factory for incoming instances. SCNet
     * never reflects into the message class, so this works across Java module boundaries.
     *
     * @param id              the id for the message, has to be unique amongst all other messages
     * @param messageClass    the class of the message
     * @param instanceFactory factory used to create a fresh instance whenever this message is received
     * @throws IllegalArgumentException if the given {@code id} is smaller than 1 or there already is a message
     *                                  registered with the given id
     */
    <T extends AbstractMessage> void registerMessage(
            short id,
            @NotNull Class<T> messageClass,
            @NotNull Supplier<? extends T> instanceFactory
    );

    /**
     * Enqueues a message to be sent at some point in the future. Implementations should wake a blocked process loop.
     * If a non-registered message is enqueued, {@link #process(Selector, SocketChannel, IMessageBus)} reports an error
     * when it tries to send it.
     *
     * @param message the message to enqueue
     */
    void enqueueMessage(@NotNull AbstractMessage message);

    /**
     * Atomically stops accepting outgoing messages and drains every frame accepted before this call. Implementations
     * must reject later {@link #enqueueMessage(AbstractMessage)} calls and retain partially written frame state until
     * the channel accepts every byte. The processor remains in drain mode until {@link #reset()}.
     *
     * @return a stage completed when all accepted outbound bytes have been written to the channel, or completed
     * exceptionally if serialization or I/O prevents the drain
     */
    default CompletionStage<Void> beginOutboundDrain() {
        CompletableFuture<Void> unsupported = new CompletableFuture<>();
        unsupported.completeExceptionally(new UnsupportedOperationException("Outbound draining is not supported"));
        return unsupported;
    }

    /**
     * Waits for I/O readiness, writes queued message data, and forwards complete incoming messages to the message bus.
     * The given selector checks for
     * {@link java.nio.channels.SelectionKey#OP_READ} and {@link java.nio.channels.SelectionKey#OP_WRITE}.
     *
     * @param selector   the selector to select the read and write keys from
     * @param channel    the channel to read from and write to
     * @param messageBus the message bus which should process received messages
     * @return {@code false} when the connection closed or an error prevents further processing; {@code true} otherwise
     */
    boolean process(@NotNull Selector selector, @NotNull SocketChannel channel, @NotNull IMessageBus messageBus);

    /**
     * Returns the error from the most recent failed {@link #process(Selector, SocketChannel, IMessageBus)} call.
     * A {@code null} value means the peer closed the connection normally.
     */
    @Nullable
    default Throwable getLastError() {
        return null;
    }

    /**
     * Resets all buffers and message queues of this message processor to put it back in its original state. This should
     * not reset the buffer size's set by {@link #setReadBufferSize(int)} or {@link #setWriteBufferSize(int)}.
     * <br>
     * This should be called when connection is lost.
     */
    void reset();

    /**
     * Retained for compatibility with processors which poll. Event-driven implementations may ignore this value.
     *
     * @param delay the delay in milliseconds
     * @see #getProcessLoopDelay()
     */
    void setProcessLoopDelay(int delay);

    /**
     * @return the configured polling delay in milliseconds
     */
    @Contract(pure = true)
    int getProcessLoopDelay();

    /**
     * Sets the initial payload serialization buffer size. A frame may grow beyond this value up to the configured
     * maximum frame size.
     *
     * @param size the new size
     * @see #getWriteBufferSize()
     */
    void setWriteBufferSize(int size);

    /**
     * @return the initial payload serialization buffer size, which defaults to {@code 16384}
     */
    @Contract(pure = true)
    int getWriteBufferSize();

    /**
     * Sets the size of each nonblocking socket read chunk.
     *
     * @param size the new size
     * @see #getReadBufferSize()
     */
    void setReadBufferSize(int size);

    /**
     * @return the socket read chunk size, which defaults to {@code 4096}
     */
    @Contract(pure = true)
    int getReadBufferSize();

    /**
     * Sets the maximum accepted or produced message payload size in bytes.
     */
    default void setMaxFrameSize(int size) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the maximum accepted or produced message payload size in bytes.
     */
    default int getMaxFrameSize() {
        return Integer.MAX_VALUE - 6;
    }

    /**
     * Sets the maximum UTF-8 byte length accepted or produced by string stream methods.
     */
    default void setMaxStringLength(int size) {
        throw new UnsupportedOperationException();
    }

    /**
     * Returns the maximum UTF-8 byte length accepted or produced by string stream methods.
     */
    default int getMaxStringLength() {
        return Integer.MAX_VALUE - 4;
    }
}
