package com.github.tth05.scnet;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Server implements AutoCloseable {

    private final Executor executor;

    private final Selector selector;
    private final ServerSocketChannel serverSocketChannel;

    private ServerClient client;

    public Server() {
        this(new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r);
            t.setName("SCNet Server");
            return t;
        }));
    }

    public Server(Executor executor) {
        this.executor = executor;
        try {
            this.selector = Selector.open();
            this.serverSocketChannel = ServerSocketChannel.open();
            this.serverSocketChannel.configureBlocking(false);
            this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void bind(SocketAddress address) {
        try {
            this.serverSocketChannel.bind(address, 1);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        this.executor.execute(() -> {
            while (true) {
                acceptClient();
                if (this.client != null) {
                    if (!this.client.process()) {
                        this.client = null;
                    }
                }
            }
        });
    }

    private void acceptClient() {
        if (!this.selector.isOpen())
            return;

        try {
            int select = this.selector.selectNow();
            if (select < 1)
                return;

            for (Iterator<SelectionKey> iterator = this.selector.selectedKeys().iterator(); iterator.hasNext(); ) {
                SelectionKey key = iterator.next();
                if (key.isAcceptable()) {
                    iterator.remove();

                    //Check if the current client is still valid
                    boolean hasClient = this.client != null && this.client.isConnected();
                    if (!hasClient)
                        this.client = null;

                    if (!hasClient) {
                        this.client = new ServerClient(this.serverSocketChannel.accept());
                    } else {
                        this.serverSocketChannel.accept().close();
                    }
                } else {
                    throw new IllegalStateException("Unknown key " + key);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClosedSelectorException ignored) {
        }
    }

    public ServerClient getClient() {
        return client;
    }

    @Override
    public void close() {
        try {
            this.selector.close();
            this.serverSocketChannel.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
