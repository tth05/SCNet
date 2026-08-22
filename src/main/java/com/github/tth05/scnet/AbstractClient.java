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

    @Nullable
    private volatile ConnectionContext connectionContext;
    @NotNull
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    @Nullable
    private volatile Throwable lastConnectionError;

    public AbstractClient() {
    }

    public AbstractClient(@Nullable SocketChannel socketChannel) {
        if (socketChannel != null) {
            try {
                installConnectedChannel(socketChannel);
            } catch (IOException e) {
                closeQuietly(socketChannel);
                throw new IllegalStateException("Unable to initialize socket channel", e);
            }
        }
    }

    /**
     * Installs an already connected channel. The caller must then call
     * {@link #startEventLoop(Executor, Runnable)}.
     */
    protected final void installConnectedChannel(@NotNull SocketChannel channel) throws IOException {
        Objects.requireNonNull(channel, "channel");
        Selector newSelector = Selector.open();
        boolean success = false;
        try {
            channel.configureBlocking(false);
            channel.register(newSelector, SelectionKey.OP_READ);
            installContext(new ConnectionContext(newSelector, channel));
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
            this.connectionState = ConnectionState.CONNECTED;
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
    protected final void startEventLoop(@NotNull Executor executor, @Nullable Runnable afterClose) {
        ConnectionContext context = this.connectionContext;
        if (context == null) {
            throw new IllegalStateException("No connected channel is installed");
        }

        context.eventLoopStarted.set(true);
        context.afterClose = afterClose;
        try {
            executor.execute(() -> runEventLoop(context));
        } catch (RuntimeException e) {
            finishConnection(context, e);
            completeConnection(context);
            throw e;
        }
    }

    private void runEventLoop(ConnectionContext context) {
        Throwable failure = null;
        try {
            if (!isCurrent(context)) {
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
        }
    }

    /**
     * Processes one selector cycle for compatibility with subclasses which drive their own event loop.
     */
    protected boolean process() {
        ConnectionContext context = this.connectionContext;
        return context != null
                && this.messageProcessor.process(context.selector, context.channel, this.messageBus);
    }

    public final boolean isConnected() {
        ConnectionContext context = this.connectionContext;
        return this.connectionState == ConnectionState.CONNECTED
                && context != null
                && context.channel.isOpen()
                && context.channel.isConnected();
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

    @Override
    public void close() {
        ConnectionContext context;
        synchronized (this.lifecycleLock) {
            context = this.connectionContext;
            if (context == null || this.connectionState == ConnectionState.DISCONNECTED) {
                this.connectionState = ConnectionState.DISCONNECTED;
                return;
            }
            this.connectionState = ConnectionState.CLOSING;
        }
        finishConnection(context, null);
        if (!context.eventLoopStarted.get()) {
            completeConnection(context);
        }
    }

    /**
     * Closes the current connection and waits until its event-loop invocation has returned.
     */
    protected final void closeAndAwaitEventLoop() throws InterruptedException {
        ConnectionContext context = this.connectionContext;
        close();
        if (context != null && context.eventLoopStarted.get()) {
            context.eventLoopStopped.await();
        }
    }

    private void finishConnection(ConnectionContext context, @Nullable Throwable cause) {
        if (!context.closed.compareAndSet(false, true)) {
            return;
        }

        boolean notify;
        synchronized (this.lifecycleLock) {
            notify = this.connectionContext == context;
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
        this.messageProcessor.reset();
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
        if (context.notifyListeners && context.failure != null) {
            notifyConnectionError(context.failure);
        }
        if (context.notifyListeners && context.disconnectedNotified.compareAndSet(false, true)) {
            onDisconnected();
        }
        context.eventLoopStopped.countDown();
    }

    private boolean isCurrent(ConnectionContext context) {
        return this.connectionContext == context && !context.closed.get();
    }

    public void setMessageProcessor(@NotNull IMessageProcessor messageProcessor) {
        if (this.connectionContext != null) {
            throw new IllegalStateException("Cannot replace the message processor while connected");
        }
        this.messageProcessor = Objects.requireNonNull(messageProcessor, "messageProcessor");
    }

    public void setMessageBus(@NotNull IMessageBus messageBus) {
        if (this.connectionContext != null) {
            throw new IllegalStateException("Cannot replace the message bus while connected");
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
        private final CountDownLatch eventLoopStopped = new CountDownLatch(1);
        @Nullable
        private volatile Runnable afterClose;
        @Nullable
        private volatile Throwable failure;
        private volatile boolean notifyListeners;

        private ConnectionContext(Selector selector, SocketChannel channel) {
            this.selector = selector;
            this.channel = channel;
        }
    }
}
