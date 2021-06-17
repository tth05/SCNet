package com.github.tth05.scnet;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public class AbstractClient implements AutoCloseable {

    protected Selector selector;
    protected SocketChannel socketChannel;

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
            this.socketChannel.register(this.selector, SelectionKey.OP_READ);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public boolean readAndWrite() {
        return true;
    }

    public boolean isConnected() {
        if (!this.socketChannel.isConnected() || !this.socketChannel.isOpen())
            return false;

        try {
            //write twice to make it fail
            this.socketChannel.write(ByteBuffer.wrap(new byte[]{1}));
            this.socketChannel.write(ByteBuffer.wrap(new byte[]{1}));
        } catch (IOException e) {
            return false;
        }

        return this.socketChannel.isConnected() && this.socketChannel.isOpen();
    }

    @Override
    public void close() {
        try {
            this.selector.close();
            this.socketChannel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
