package com.github.tth05.scnet;

import org.jetbrains.annotations.NotNull;

import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Client extends AbstractClient {

    /**
     * The executor on which the client thread will run
     */
    @NotNull
    private final Executor executor;

    public Client() {
        this(new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r);
            t.setName("SCNet Client");
            return t;
        }));
    }

    /**
     * @param executor an executor on which the client thread will run. This executor needs to have one available
     *                 thread.
     */
    public Client(@NotNull Executor executor) {
        super();
        this.executor = executor;
    }

    /**
     * Tries to connect this client to the given {@code address} using {@link #connect(SocketAddress)}. After each
     * failed attempt, the current thread will wait at least {@code timeout} milliseconds.
     *
     * @param address the address to connect to
     * @param timeout the timeout in milliseconds between each failed attempt
     * @param retries the number of times the method should try to establish a connection
     * @return {@code true} if the connection succeeded; {@code false} otherwise
     */
    public boolean connect(@NotNull SocketAddress address, int timeout, int retries) {
        for (int i = 0; i < retries; i++) {
            if (connect(address))
                return true;
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException ignored) {}
        }

        return false;
    }

    /**
     * Tries to connect this client to the given {@code address}.
     *
     * @param address the address to connect to
     * @return {@code true} if the connection succeeded; {@code false} otherwise
     */
    public boolean connect(@NotNull SocketAddress address) {
        try {
            close();
            initChannelAndSelector(null);

            this.socketChannel.register(this.selector, SelectionKey.OP_CONNECT);
            this.socketChannel.connect(address);

            int selected = this.selector.select(1000);
            if (selected < 1)
                return false;

            for (Iterator<SelectionKey> iterator = this.selector.selectedKeys().iterator(); iterator.hasNext(); ) {
                SelectionKey key = iterator.next();
                if (!key.isConnectable())
                    throw new IllegalStateException("Invalid key");

                key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                iterator.remove();
            }

            if (!this.socketChannel.finishConnect())
                return false;

            if (this.socketChannel.isConnected()) {
                this.executor.execute(() -> {
                    while (true) {
                        if (!this.process()) {
                            this.messageProcessor.reset();
                            this.close();
                            return;
                        }
                    }
                });

                //TODO: send welcome message between client and server. A connection might not mean that both can
                // communicate now
                return true;
            }

            return false;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
