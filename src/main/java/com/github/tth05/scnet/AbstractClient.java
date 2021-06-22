package com.github.tth05.scnet;

import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.IMessageProcessor;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Base class for any client.
 */
public abstract class AbstractClient implements AutoCloseable {

    /**
     * Selector used to check for {@link SelectionKey#OP_CONNECT}, {@link SelectionKey#OP_READ} and
     * {@link SelectionKey#OP_WRITE}.
     */
    @NotNull
    protected Selector selector;
    /**
     * Internal socket channel used for the connection
     */
    @NotNull
    protected SocketChannel socketChannel;

    /**
     * The message bus
     */
    @NotNull
    protected IMessageBus messageBus = new DefaultMessageBus();
    /**
     * The message processor
     */
    @NotNull
    protected IMessageProcessor messageProcessor = new DefaultMessageProcessor();

    /**
     * A lock to prevent {@link #close()}ing the selector and channel while the {@link #process()} loop is working.
     */
    @NotNull
    private final ReentrantLock selectorLock = new ReentrantLock();

    public AbstractClient() {
        this(null);
    }

    public AbstractClient(@Nullable SocketChannel socketChannel) {
        initChannelAndSelector(socketChannel);
    }

    /**
     * Initializes the {@link #selector} and the {@link #socketChannel}. If the {@code socketChannel} parameter is null,
     * a new channel is opened.
     * <br>
     * This method is used when connecting because a {@link SocketChannel} cannot be bound to a different address.
     *
     * @param socketChannel a pre-existing {@link SocketChannel} to use
     */
    protected void initChannelAndSelector(@Nullable SocketChannel socketChannel) {
        try {
            this.selector = Selector.open();

            if (socketChannel != null)
                this.socketChannel = socketChannel;
            else
                this.socketChannel = SocketChannel.open();

            this.socketChannel.configureBlocking(false);
            this.socketChannel.register(this.selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Wrapper method for {@link IMessageProcessor#process(Selector, SocketChannel, IMessageBus)} which includes a lock
     * to prevent calls to {@link #close()} while messages are being processed.
     *
     * @return same as {@link IMessageProcessor#process(Selector, SocketChannel, IMessageBus)}
     */
    protected boolean process() {
        //Allow other threads to acquire this lock before us. The while loop in the Client won't allow other threads to
        // acquire this lock
        if (this.selectorLock.hasQueuedThreads()) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException ignored) {}
        }

        //Prevent #close call while we're doing this
        this.selectorLock.lock();
        if (!this.selector.isOpen()) {
            this.selectorLock.unlock();
            return false;
        }
        boolean b = this.messageProcessor.process(this.selector, this.socketChannel, this.messageBus);
        this.selectorLock.unlock();
        return b;
    }

    public boolean isConnected() {
        if (!this.socketChannel.isConnected() || !this.socketChannel.isOpen())
            return false;

        try {
            //Write twice to make it fail
            this.socketChannel.write(ByteBuffer.wrap(new byte[]{0, 0, 0, 0, 0, 0}));
            this.socketChannel.write(ByteBuffer.wrap(new byte[]{0, 0, 0, 0, 0, 0}));
        } catch (IOException e) {
            return false;
        }

        return this.socketChannel.isConnected() && this.socketChannel.isOpen();
    }

    @Override
    public void close() {
        try {
            this.selectorLock.lock();
            this.selector.close();
            this.selectorLock.unlock();
            this.socketChannel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setMessageProcessor(@NotNull IMessageProcessor messageProcessor) {
        this.messageProcessor = messageProcessor;
    }

    public void setMessageBus(@NotNull IMessageBus messageBus) {
        this.messageBus = messageBus;
    }

    @NotNull
    public IMessageProcessor getMessageProcessor() {
        return messageProcessor;
    }

    @NotNull
    public IMessageBus getMessageBus() {
        return messageBus;
    }
}
