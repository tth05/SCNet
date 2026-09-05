package com.github.tth05.scnet;

import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.IMessageProcessor;
import com.github.tth05.scnet.message.impl.DefaultMessageBus;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for a single socket connection.
 */
public abstract class AbstractClient implements AutoCloseable {

    /**
     * The selector for the current connection. The connection event loop owns selector operations.
     */
    @Nullable
    protected volatile Selector selector;

    /**
     * The socket channel for the current connection.
     */
    @Nullable
    protected volatile SocketChannel socketChannel;

    @NotNull
    protected volatile IMessageBus messageBus = new DefaultMessageBus();

    @NotNull
    protected volatile IMessageProcessor messageProcessor = new DefaultMessageProcessor();

    @NotNull
    protected List<IConnectionListener> connectionListeners = new CopyOnWriteArrayList<>();

    private final Object lifecycleLock = new Object();
    private final ThreadLocal<ConnectionContext> processingContext = new ThreadLocal<>();

    @Nullable
    private volatile ConnectionContext connectionContext;
    @NotNull
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    @Nullable
    private volatile Throwable lastConnectionError;

    /** Installs a close callback before the endpoint can be published or closed, even if its loop never starts. */
    protected final void installConnectedChannel(@NotNull SocketChannel channel, @Nullable Runnable afterClose) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Selector newSelector = Selector.open();
        boolean success = false;
        try {
            channel.configureBlocking(false);
            channel.register(newSelector, SelectionKey.OP_READ);
            ConnectionContext context = new ConnectionContext(newSelector, channel);
            context.afterClose = afterClose;
            installContext(context);
            success = true;
        } finally {
            if (!success) {
                closeQuietly(newSelector);
            }
        }
    }

    /**
     * Installs a channel and selector prepared by a connecting client.
     */
    protected final void installConnectedChannel(@NotNull Selector newSelector, @NotNull SocketChannel channel) {
        installContext(new ConnectionContext(newSelector, channel));
    }

    private void installContext(ConnectionContext context) {
        synchronized (this.lifecycleLock) {
            if (this.connectionContext != null) {
                throw new IllegalStateException("A connection is already installed");
            }
            this.connectionContext = context;
            this.selector = context.selector;
            this.socketChannel = context.channel;
            this.connectionState = context.channel.isConnected()
                    ? ConnectionState.CONNECTED
                    : ConnectionState.DISCONNECTED;
            this.lastConnectionError = null;
        }
    }

    protected final void setConnecting() {
        synchronized (this.lifecycleLock) {
            if (this.connectionContext != null) {
                throw new IllegalStateException("A connection is already installed");
            }
            this.connectionState = ConnectionState.CONNECTING;
            this.lastConnectionError = null;
        }
    }

    protected final void setConnectFailed(@Nullable Throwable cause) {
        synchronized (this.lifecycleLock) {
            if (this.connectionContext == null) {
                this.connectionState = ConnectionState.DISCONNECTED;
                this.lastConnectionError = cause;
            }
        }
    }

    /**
     * Starts the transport event loop. The executor must provide one thread for the lifetime of the connection.
     */
    protected final void startEventLoop(@NotNull Executor executor) {
        startEventLoop(executor, false);
    }

    /** Starts a published endpoint unless a concurrent close already released it. */
    protected final void startEventLoopIfConnected(@NotNull Executor executor) {
        startEventLoop(executor, true);
    }

    private void startEventLoop(Executor executor, boolean allowClosed) {
        ConnectionContext context;
        synchronized (this.lifecycleLock) {
            context = this.connectionContext;
            if (context == null || context.closed.get()) {
                if (allowClosed) {
                    return;
                }
                throw new IllegalStateException("No connected channel is installed");
            }
            if (!context.eventLoopStarted.compareAndSet(false, true)) {
                throw new IllegalStateException("The connection event loop is already started");
            }
        }

        try {
            executor.execute(() -> runEventLoop(context));
        } catch (RuntimeException e) {
            finishConnection(context, e);
            completeConnection(context);
            throw e;
        }
    }

    private void runEventLoop(ConnectionContext context) {
        context.eventLoopRunning.set(true);
        this.processingContext.set(context);
        Throwable failure = null;
        try {
            if (!beginConnectedNotification(context)) {
                return;
            }

            notifyConnected();
            while (isCurrent(context) && context.selector.isOpen() && context.channel.isOpen()) {
                if (!this.messageProcessor.process(context.selector, context.channel, this.messageBus)) {
                    failure = this.messageProcessor.getLastError();
                    break;
                }
            }
        } catch (Throwable t) {
            failure = t;
        } finally {
            finishConnection(context, failure);
            completeConnection(context);
            this.processingContext.remove();
        }
    }

    public boolean isConnected() {
        ConnectionContext context = this.connectionContext;
        boolean connected = context != null
                && context.channel.isOpen()
                && context.channel.isConnected()
                && this.connectionState != ConnectionState.CLOSING;
        if (connected) {
            markChannelConnected(context);
        }
        return connected;
    }

    @NotNull
    public final ConnectionState getConnectionState() {
        return this.connectionState;
    }

    @Nullable
    public final Throwable getLastConnectionError() {
        return this.lastConnectionError;
    }

    public void addConnectionListener(@NotNull IConnectionListener listener) {
        this.connectionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeConnectionListener(@NotNull IConnectionListener listener) {
        this.connectionListeners.remove(listener);
    }

    private void notifyConnected() {
        for (IConnectionListener listener : this.connectionListeners) {
            try {
                listener.onConnected();
            } catch (Throwable ignored) {
            }
        }
    }

    private void notifyConnectionError(Throwable cause) {
        for (IConnectionListener listener : this.connectionListeners) {
            try {
                listener.onConnectionError(cause);
            } catch (Throwable ignored) {
            }
        }
    }

    protected void onDisconnected() {
        for (IConnectionListener listener : this.connectionListeners) {
            try {
                listener.onDisconnected();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Stops accepting outgoing messages, drains every frame already accepted by the message processor, and then
     * closes this connection. Later enqueue attempts are rejected by the processor. Repeated calls while the same
     * connection is draining return the same stage.
     *
     * @return a stage completed after transport teardown and disconnect notification, or completed exceptionally if
     * serialization, I/O, peer closure, or a competing immediate close prevents the drain
     */
    public CompletionStage<Void> closeAfterPendingWrites() {
        ConnectionContext context;
        CompletableFuture<Void> closeFuture;
        CompletionStage<Void> drainStage;
        synchronized (this.lifecycleLock) {
            context = this.connectionContext;
            if (context == null || context.closed.get() || !context.channel.isConnected()) {
                return failedStage(new IllegalStateException("No connected channel is available to drain"));
            }
            if (context.drainCloseFuture != null) {
                return context.drainCloseFuture;
            }

            closeFuture = new CompletableFuture<>();
            context.drainCloseFuture = closeFuture;
            try {
                drainStage = Objects.requireNonNull(
                        this.messageProcessor.beginOutboundDrain(),
                        "messageProcessor returned a null outbound drain stage"
                );
            } catch (Throwable t) {
                context.drainFailure = t;
                finishConnection(context, t);
                if (canComplete(context)) {
                    completeConnection(context);
                }
                return closeFuture;
            }
        }

        drainStage.whenComplete((ignored, error) -> {
            if (error == null) {
                context.outboundDrainCompleted = true;
                finishConnection(context, null);
            } else {
                Throwable cause = unwrapCompletionFailure(error);
                context.drainFailure = cause;
                finishConnection(context, cause);
            }
            if (canComplete(context)) {
                completeConnection(context);
            }
        });
        context.selector.wakeup();
        return closeFuture;
    }

    @Override
    public void close() {
        ConnectionContext context;
        synchronized (this.lifecycleLock) {
            context = this.connectionContext;
            if (context == null) {
                this.connectionState = ConnectionState.DISCONNECTED;
                return;
            }
            this.connectionState = ConnectionState.CLOSING;
        }
        finishConnection(context, null);
        if (canComplete(context)) {
            completeConnection(context);
        }
    }

    /**
     * Closes the current connection and waits until its transport resources are released and its processor is reset.
     * This method fails immediately when called by the thread currently processing that connection.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws IllegalStateException if the current thread is processing the connection being closed
     */
    protected final void closeAndAwaitEventLoop() throws InterruptedException {
        requireConnectionReplacementAllowed();
        ConnectionContext context = this.connectionContext;
        close();
        if (context != null) {
            context.transportReleased.await();
        }
    }

    /** Checks callback reentrancy before a connecting client acquires its connection-attempt lock. */
    protected final void requireConnectionReplacementAllowed() {
        ConnectionContext context = this.connectionContext;
        if (context != null && this.processingContext.get() == context) {
            throw new IllegalStateException(
                    "Cannot replace a connection from its transport callback; schedule the operation on another thread"
            );
        }
    }

    private void finishConnection(ConnectionContext context, @Nullable Throwable cause) {
        if (!context.closed.compareAndSet(false, true)) {
            return;
        }

        boolean notify;
        synchronized (this.lifecycleLock) {
            notify = this.connectionContext == context && context.connectedNotified.get();
            if (notify) {
                this.connectionState = ConnectionState.DISCONNECTED;
                if (cause != null) {
                    this.lastConnectionError = cause;
                }
            }
        }
        context.notifyListeners = notify;
        context.failure = cause;

        context.selector.wakeup();
        closeQuietly(context.channel);
        closeQuietly(context.selector);
    }

    private void completeConnection(ConnectionContext context) {
        if (!context.completed.compareAndSet(false, true)) {
            return;
        }
        try {
            this.messageProcessor.reset();
        } catch (Throwable t) {
            if (context.failure == null) {
                context.failure = t;
            }
        }
        synchronized (this.lifecycleLock) {
            if (this.connectionContext == context) {
                this.connectionContext = null;
                this.selector = null;
                this.socketChannel = null;
                this.connectionState = ConnectionState.DISCONNECTED;
            }
        }
        if (context.afterClose != null) {
            try {
                context.afterClose.run();
            } catch (Throwable ignored) {
            }
        }
        context.transportReleased.countDown();
        if (context.notifyListeners && context.failure != null) {
            notifyConnectionError(context.failure);
        }
        if (context.notifyListeners && context.disconnectedNotified.compareAndSet(false, true)) {
            onDisconnected();
        }
        completeDrainClose(context);
    }

    private boolean isCurrent(ConnectionContext context) {
        return this.connectionContext == context && !context.closed.get();
    }

    private boolean beginConnectedNotification(ConnectionContext context) {
        synchronized (this.lifecycleLock) {
            if (this.connectionContext != context || context.closed.get()) {
                return false;
            }
            context.connectedNotified.set(true);
            this.connectionState = ConnectionState.CONNECTED;
            return true;
        }
    }

    private void markChannelConnected(ConnectionContext context) {
        synchronized (this.lifecycleLock) {
            if (this.connectionContext == context
                    && !context.closed.get()
                    && this.connectionState != ConnectionState.CLOSING) {
                this.connectionState = ConnectionState.CONNECTED;
            }
        }
    }

    private boolean canComplete(ConnectionContext context) {
        return !context.eventLoopRunning.get();
    }

    private void completeDrainClose(ConnectionContext context) {
        CompletableFuture<Void> closeFuture = context.drainCloseFuture;
        if (closeFuture == null) {
            return;
        }
        if (context.outboundDrainCompleted) {
            closeFuture.complete(null);
            return;
        }

        Throwable cause = context.drainFailure != null ? context.drainFailure : context.failure;
        if (cause == null) {
            cause = new IOException("Connection closed before pending outbound messages were drained");
        }
        closeFuture.completeExceptionally(cause);
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static <T> CompletionStage<T> failedStage(Throwable failure) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(failure);
        return future;
    }

    public void setMessageProcessor(@NotNull IMessageProcessor messageProcessor) {
        Objects.requireNonNull(messageProcessor, "messageProcessor");
        synchronized (this.lifecycleLock) {
            if (hasActiveConnection()) {
                throw new IllegalStateException("Cannot replace the message processor while connected");
            }
            this.messageProcessor = messageProcessor;
        }
    }

    public void setMessageBus(@NotNull IMessageBus messageBus) {
        Objects.requireNonNull(messageBus, "messageBus");
        synchronized (this.lifecycleLock) {
            if (hasActiveConnection()) {
                throw new IllegalStateException("Cannot replace the message bus while connected");
            }
            this.messageBus = messageBus;
        }
    }

    @NotNull
    public IMessageProcessor getMessageProcessor() {
        return this.messageProcessor;
    }

    @NotNull
    public IMessageBus getMessageBus() {
        return this.messageBus;
    }

    private boolean hasActiveConnection() {
        ConnectionContext context = this.connectionContext;
        if (context == null) {
            return false;
        }
        return context.eventLoopStarted.get() || context.eventLoopRunning.get();
    }

    private static void closeQuietly(@Nullable AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static final class ConnectionContext {
        private final Selector selector;
        private final SocketChannel channel;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();
        private final AtomicBoolean disconnectedNotified = new AtomicBoolean();
        private final AtomicBoolean eventLoopStarted = new AtomicBoolean();
        private final AtomicBoolean eventLoopRunning = new AtomicBoolean();
        private final AtomicBoolean connectedNotified = new AtomicBoolean();
        private final CountDownLatch transportReleased = new CountDownLatch(1);
        @Nullable
        private volatile Runnable afterClose;
        @Nullable
        private volatile Throwable failure;
        private volatile boolean notifyListeners;
        @Nullable
        private volatile CompletableFuture<Void> drainCloseFuture;
        @Nullable
        private volatile Throwable drainFailure;
        private volatile boolean outboundDrainCompleted;

        private ConnectionContext(Selector selector, SocketChannel channel) {
            this.selector = selector;
            this.channel = channel;
        }
    }
}
