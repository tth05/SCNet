package com.github.tth05.scnet;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Client extends AbstractClient {

    private static final int CONNECT_TIMEOUT_MILLIS = 1000;

    @NotNull
    private final Executor executor;
    private final Object connectionAttemptLock = new Object();

    public Client() {
        this(createDefaultExecutor());
    }

    /**
     * @param executor an executor which can dedicate one thread to the client while it is connected
     */
    public Client(@NotNull Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Tries to connect repeatedly. The current thread waits between failed attempts.
     */
    public boolean connect(@NotNull SocketAddress address, int timeout, int retries) {
        Objects.requireNonNull(address, "address");
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }
        if (retries < 0) {
            throw new IllegalArgumentException("retries cannot be negative");
        }

        for (int i = 0; i < retries; i++) {
            if (connect(address)) {
                return true;
            }
            if (i + 1 >= retries) {
                break;
            }
            try {
                Thread.sleep(timeout);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Tries to connect this client to the given address.
     *
     * @throws IllegalStateException if called from a callback currently executing on this client's transport thread
     */
    public boolean connect(@NotNull SocketAddress address) {
        Objects.requireNonNull(address, "address");
        requireConnectionReplacementAllowed();
        synchronized (this.connectionAttemptLock) {
            try {
                // Callbacks may close the old connection while this attempt waits for them to finish.
                closeAndAwaitEventLoop();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            synchronized (this) {
                return connectAfterTransportReleased(address);
            }
        }
    }

    private boolean connectAfterTransportReleased(SocketAddress address) {
        setConnecting();
        Selector newSelector = null;
        SocketChannel newChannel = null;
        try {
            newSelector = Selector.open();
            newChannel = SocketChannel.open();
            newChannel.configureBlocking(false);
            SelectionKey connectKey = newChannel.register(newSelector, SelectionKey.OP_CONNECT);

            boolean connectedImmediately = newChannel.connect(address);
            if (!connectedImmediately && !awaitConnection(newSelector, newChannel)) {
                setConnectFailed(new ConnectException("Connection timed out"));
                closeQuietly(newChannel);
                closeQuietly(newSelector);
                return false;
            }

            connectKey.interestOps(SelectionKey.OP_READ);
            getMessageProcessor().reset();
            installConnectedChannel(newSelector, newChannel);
            startEventLoop(this.executor, null);
            return true;
        } catch (ConnectException e) {
            setConnectFailed(e);
            closeQuietly(newChannel);
            closeQuietly(newSelector);
            return false;
        } catch (IOException e) {
            setConnectFailed(e);
            closeQuietly(newChannel);
            closeQuietly(newSelector);
            return false;
        } catch (RuntimeException e) {
            setConnectFailed(e);
            closeQuietly(newChannel);
            closeQuietly(newSelector);
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        super.close();
    }

    private static boolean awaitConnection(Selector selector, SocketChannel channel) throws IOException {
        if (selector.select(CONNECT_TIMEOUT_MILLIS) == 0) {
            return false;
        }

        for (Iterator<SelectionKey> iterator = selector.selectedKeys().iterator(); iterator.hasNext(); ) {
            SelectionKey key = iterator.next();
            iterator.remove();
            if (key.isConnectable() && channel.finishConnect()) {
                return true;
            }
        }
        return channel.isConnected();
    }

    private static Executor createDefaultExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0,
                1,
                1L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "SCNet Client");
                    thread.setDaemon(true);
                    return thread;
                }
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
