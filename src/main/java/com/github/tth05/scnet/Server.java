package com.github.tth05.scnet;

import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.IMessageProcessor;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class Server implements AutoCloseable {

    @NotNull
    private final Executor executor;
    @NotNull
    private volatile IMessageBus messageBus = new DefaultMessageBus();
    @NotNull
    private volatile IMessageProcessor messageProcessor = new DefaultMessageProcessor();
    @NotNull
    private final List<IConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();
    @NotNull
    private final Selector selector;
    @NotNull
    private final ServerSocketChannel serverSocketChannel;
    private final AtomicBoolean bound = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    @Nullable
    private volatile ServerClient client;
    @Nullable
    private volatile Throwable lastConnectionError;

    public Server() {
        this(createDefaultExecutor());
    }

    /**
     * @param executor an executor which can dedicate one thread to accepting connections while the server is open
     */
    public Server(@NotNull Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        try {
            this.selector = Selector.open();
            this.serverSocketChannel = ServerSocketChannel.open();
            this.serverSocketChannel.configureBlocking(false);
            this.serverSocketChannel.register(this.selector, SelectionKey.OP_ACCEPT);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize server", e);
        }
    }

    /**
     * Binds this server and starts its accept event loop.
     */
    public void bind(@NotNull SocketAddress address) {
        Objects.requireNonNull(address, "address");
        if (!this.bound.compareAndSet(false, true)) {
            throw new IllegalStateException("Server is already bound");
        }
        if (this.closed.get()) {
            throw new IllegalStateException("Server is closed");
        }

        try {
            this.serverSocketChannel.bind(address, 1);
            this.executor.execute(this::runAcceptLoop);
        } catch (IOException | RuntimeException e) {
            this.lastConnectionError = e;
            close();
            throw new IllegalStateException("Unable to bind server", e);
        }
    }

    private void runAcceptLoop() {
        try {
            while (!this.closed.get() && this.selector.isOpen()) {
                this.selector.select();
                for (Iterator<SelectionKey> iterator = this.selector.selectedKeys().iterator(); iterator.hasNext(); ) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (key.isAcceptable()) {
                        acceptAvailableClients();
                    }
                }
            }
        } catch (ClosedSelectorException ignored) {
        } catch (IOException e) {
            if (!this.closed.get()) {
                this.lastConnectionError = e;
                notifyConnectionError(e);
                close();
            }
        }
    }

    private void acceptAvailableClients() throws IOException {
        SocketChannel accepted;
        while ((accepted = this.serverSocketChannel.accept()) != null) {
            ServerClient currentClient = this.client;
            if (currentClient != null && currentClient.isConnected()) {
                accepted.close();
                continue;
            }

            if (currentClient != null) {
                accepted.close();
                continue;
            }

            this.messageProcessor.reset();
            try {
                this.client = new ServerClient(
                        accepted,
                        this.connectionListeners,
                        this.messageProcessor,
                        this.messageBus,
                        this::onClientClosed
                );
            } catch (IOException | RuntimeException e) {
                accepted.close();
                this.lastConnectionError = e;
                notifyConnectionError(e);
            }
        }
    }

    private void onClientClosed(ServerClient closedClient) {
        if (this.client == closedClient) {
            this.lastConnectionError = closedClient.getLastConnectionError();
            this.client = null;
        }
    }

    public boolean isClientConnected() {
        ServerClient currentClient = this.client;
        return currentClient != null && currentClient.isConnected();
    }

    public void closeClient() {
        ServerClient currentClient = this.client;
        if (currentClient != null) {
            currentClient.close();
        }
    }

    /**
     * Drains the current client's accepted outbound frames and closes that client after the final byte is written.
     *
     * @return a stage completed after the client connection closes, or completed exceptionally when no client is
     * connected or the pending data cannot be written
     */
    public CompletionStage<Void> closeClientAfterPendingWrites() {
        ServerClient currentClient = this.client;
        if (currentClient == null || !currentClient.isConnected()) {
            CompletableFuture<Void> unavailable = new CompletableFuture<>();
            unavailable.completeExceptionally(new IllegalStateException("No client is connected"));
            return unavailable;
        }
        return currentClient.closeAfterPendingWrites();
    }

    @NotNull
    public SocketAddress getLocalAddress() {
        try {
            SocketAddress address = this.serverSocketChannel.getLocalAddress();
            if (address == null) {
                throw new IllegalStateException("Server is not bound");
            }
            return address;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to query server address", e);
        }
    }

    @Nullable
    public Throwable getLastConnectionError() {
        return this.lastConnectionError;
    }

    @Override
    public void close() {
        if (!this.closed.compareAndSet(false, true)) {
            return;
        }

        this.selector.wakeup();
        ServerClient currentClient = this.client;
        if (currentClient != null) {
            currentClient.close();
        }
        closeQuietly(this.serverSocketChannel);
        closeQuietly(this.selector);
    }

    public void addConnectionListener(@NotNull IConnectionListener listener) {
        this.connectionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeConnectionListener(@NotNull IConnectionListener listener) {
        this.connectionListeners.remove(listener);
    }

    private void notifyConnectionError(Throwable cause) {
        for (IConnectionListener listener : this.connectionListeners) {
            try {
                listener.onConnectionError(cause);
            } catch (Throwable ignored) {
            }
        }
    }

    public void setMessageProcessor(@NotNull IMessageProcessor messageProcessor) {
        if (this.client != null) {
            throw new IllegalStateException("Cannot replace the message processor while a client is connected");
        }
        this.messageProcessor = Objects.requireNonNull(messageProcessor, "messageProcessor");
    }

    public void setMessageBus(@NotNull IMessageBus messageBus) {
        if (this.client != null) {
            throw new IllegalStateException("Cannot replace the message bus while a client is connected");
        }
        this.messageBus = Objects.requireNonNull(messageBus, "messageBus");
    }

    @NotNull
    public IMessageProcessor getMessageProcessor() {
        return this.messageProcessor;
    }

    @NotNull
    public IMessageBus getMessageBus() {
        return this.messageBus;
    }

    private static Executor createDefaultExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0,
                1,
                1L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "SCNet Server Accept");
                    thread.setDaemon(true);
                    return thread;
                }
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
