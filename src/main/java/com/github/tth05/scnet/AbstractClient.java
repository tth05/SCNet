package com.github.tth05.scnet;

import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.IMessageProcessor;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import org.jetbrains.annotations.NotNull;

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

    @NotNull
    protected Selector selector;
    @NotNull
    protected SocketChannel socketChannel;

    @NotNull
    protected IMessageBus messageBus = new DefaultMessageBus();
    @NotNull
    protected IMessageProcessor messageProcessor = new DefaultMessageProcessor();

    @NotNull
    private final ReentrantLock selectorLock = new ReentrantLock();

    public AbstractClient() {
        this(null);
    }

    public AbstractClient(SocketChannel socketChannel) {
        initChannelAndSelector(socketChannel);
    }

    protected void initChannelAndSelector(SocketChannel socketChannel) {
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

    public void setMessageProcessor(IMessageProcessor messageProcessor) {
        this.messageProcessor = messageProcessor;
    }

    public void setMessageBus(IMessageBus messageBus) {
        this.messageBus = messageBus;
    }

    public IMessageProcessor getMessageProcessor() {
        return messageProcessor;
    }

    public IMessageBus getMessageBus() {
        return messageBus;
    }
}
