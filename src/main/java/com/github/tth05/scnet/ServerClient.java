package com.github.tth05.scnet;

import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.IMessageProcessor;
import org.jetbrains.annotations.NotNull;

import java.nio.channels.SocketChannel;
import java.util.List;

/**
 * Wrapper class for any client which is accepted by the server.
 */
class ServerClient extends AbstractClient {

    ServerClient(@NotNull SocketChannel socketChannel, @NotNull List<IConnectionListener> connectionListeners, @NotNull IMessageProcessor messageProcessor, @NotNull IMessageBus messageBus) {
        super(socketChannel);
        setMessageProcessor(messageProcessor);
        setMessageBus(messageBus);

        this.connectionListeners = connectionListeners;
        synchronized (this.connectionListeners) {
            this.connectionListeners.forEach(IConnectionListener::onConnected);
        }
    }

    @Override
    public void close() {
        super.close();
        onDisconnected();
    }
}
