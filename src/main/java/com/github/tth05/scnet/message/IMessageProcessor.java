package com.github.tth05.scnet.message;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/**
 * A message processor will send enqueued messages and forward received messages to a {@link IMessageBus}.
 * <br>
 * To recognize messages, they will have to be registered with the message processor using
 * {@link #registerMessage(short, Class)}.
 */
public interface IMessageProcessor {

    /**
     * Registers a message with this message processor, so it can be received/sent. Any message registered using this
     * method requires a public default constructor. This allows for easy instantiation when receiving messages.
     * <br>
     * The id may be anything greater than 0. 0 is reserved for {@link com.github.tth05.scnet.message.impl.EmptyMessage}
     *
     * @param id           the id for the message, has to be unique amongst all other messages
     * @param messageClass the class of the message
     * @throws IllegalArgumentException if the given {@code id} is smaller than 1 or there already is a message
     *                                  registered with the given id.
     */
    <T extends AbstractMessage> void registerMessage(short id, @NotNull Class<T> messageClass);

    /**
     * Enqueues a message to be sent at some point in the future. If a non-registered message is enqueued,
     * {@link #process(Selector, SocketChannel, IMessageBus)} when raise and exception when it tries to send it.
     *
     * @param message the message to enqueue
     */
    void enqueueMessage(@NotNull AbstractMessage message);

    /**
     * This method will write all enqueued messages to the {@code channel} and then read all available messages and
     * forward them to the {@code messageBus}. The given {@code selector} will be used to check for
     * {@link java.nio.channels.SelectionKey#OP_READ} and {@link java.nio.channels.SelectionKey#OP_WRITE}.
     *
     * @param selector   the selector to select the read and write keys from
     * @param channel    the channel to read from and write to
     * @param messageBus the message bus which should process received messages
     * @return {@code false} if something went wrong during reading or writing, and the process loop may not be able to
     * continue in the future; {@code true} otherwise.
     */
    boolean process(@NotNull Selector selector, @NotNull SocketChannel channel, @NotNull IMessageBus messageBus);

    /**
     * @param delay the delay in milliseconds
     * @see #getProcessLoopDelay()
     */
    void setProcessLoopDelay(int delay);

    /**
     * @return the delay that {@link #process(Selector, SocketChannel, IMessageBus)} will wait before performing any
     * operations. This will save the CPU from unnecessary strain. Defaults to {@code 5}ms.
     */
    @Contract(pure = true)
    int getProcessLoopDelay();

    /**
     * This method will replace the current buffer with a new buffer of the given size.
     *
     * @param size the new size
     * @see #getWriteBufferSize()
     */
    void setWriteBufferSize(int size);

    /**
     * @return the size of the current write buffer. Queued packets will be written to this first, and flushed to the
     * socket once it fills up. Increasing the size of this will help when sending a lot of data. Defaults to
     * {@code 16384}.
     */
    @Contract(pure = true)
    int getWriteBufferSize();

    /**
     * This method will replace the current buffer with a new buffer of the given size.
     *
     * @param size the new size
     * @see #getReadBufferSize()
     */
    void setReadBufferSize(int size);

    /**
     * @return the size of the current read buffer. Available data will be read into this buffer and then processed into
     * individual messages. Increasing the size of this will help when receiving a lot of data. Defaults to
     * {@code 4096}.
     */
    @Contract(pure = true)
    int getReadBufferSize();
}
