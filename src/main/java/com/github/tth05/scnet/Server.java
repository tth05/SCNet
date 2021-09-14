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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Server implements AutoCloseable {

    /**
     * The executor on which the server thread will run
     */
    @NotNull
    private final Executor executor;

    /**
     * The message bus
     */
    @NotNull
    private IMessageBus messageBus = new DefaultMessageBus();
    /**
     * The message processor
     */
    @NotNull
    private IMessageProcessor messageProcessor = new DefaultMessageProcessor();

    /**
     * Selector used to check for {@link SelectionKey#OP_ACCEPT}.
     */
    private final Selector selector;
    /**
     * Internal socket channel used to accept clients
     */
    private final ServerSocketChannel serverSocketChannel;

    /**
     * These listeners are notified when the server establishes a connection with a client
     */
    private final List<Runnable> onConnectionListeners = new ArrayList<>();

    /**
     * The currently connected client
     */
    @Nullable
    private ServerClient client;

    public Server() {
        this(new ThreadPoolExecutor(1, 1,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r);
            t.setName("SCNet Server");
            t.setDaemon(true);
            return t;
        }));
    }

    /**
     * @param executor an executor on which the server thread will run. This executor needs to have one available
     *                 thread.
     */
    public Server(@NotNull Executor executor) {
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

    /**
     * Binds this server to the given {@code address} and starts listening for clients.
     *
     * @param address the address to bind this server to
     */
    public void bind(SocketAddress address) {
        try {
            this.serverSocketChannel.bind(address, 1);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        this.executor.execute(() -> {
            while (this.selector.isOpen()) {
                acceptClient();
                if (this.client != null) {
                    if (!this.client.process()) {
                        this.messageProcessor.reset();
                        this.client.close();
                        this.client = null;
                    }
                }
            }
        });
    }

    /**
     * Queries this {@link #selector} for {@link SelectionKey#OP_ACCEPT} and accepts any new clients. If the connection
     * with the current client is still open, any new clients trying to connect will get their connection closed.
     */
    private void acceptClient() {
        if (!this.selector.isOpen())
            return;

        try {
            int select;
            if (this.client == null) //We add some delay here to save the processor
                select = this.selector.select(10);
            else
                select = this.selector.selectNow();

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

                    if (!hasClient) { //Accept a new client
                        this.messageProcessor.reset();

                        this.client = new ServerClient(
                                this.serverSocketChannel.accept(),
                                getMessageProcessor(),
                                getMessageBus()
                        );

                        synchronized (this.onConnectionListeners) {
                            this.onConnectionListeners.forEach(Runnable::run);
                        }
                    } else { //Block other clients trying to connect
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

    /**
     * Adds a listener that is notified when the server establishes a new connection with a client. The listener is not
     * notified for declined connections.
     */
    public void addOnConnectionListener(Runnable r) {
        synchronized (this.onConnectionListeners) {
            this.onConnectionListeners.add(r);
        }
    }

    /**
     * Removes a connection listener
     */
    public void removeOnConnectionListener(Runnable r) {
        synchronized (this.onConnectionListeners) {
            this.onConnectionListeners.remove(r);
        }
    }

    /**
     * @return {@code true} if a client is connected and the connection is open; {@code false} otherwise
     */
    public boolean isClientConnected() {
        return this.client != null && this.client.isConnected();
    }

    /**
     * Closes the connection to the current client
     */
    public void closeClient() {
        if (this.client != null)
            this.client.close();
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
