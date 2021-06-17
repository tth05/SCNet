package com.github.tth05.scnet;

import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.IMessageProcessor;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class AbstractClient implements AutoCloseable {

    protected Selector selector;
    protected SocketChannel socketChannel;

    protected IMessageBus messageBus = new DefaultMessageBus();
    protected IMessageProcessor messageProcessor = new DefaultMessageProcessor();

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
        //Prevent close call while reading we're doing this
        synchronized (this.selector) {
            if (!this.selector.isOpen())
                return false;
            return this.messageProcessor.process(this.selector, this.socketChannel, this.messageBus);
        }
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
            synchronized (this.selector) {
                this.selector.close();
            }
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
