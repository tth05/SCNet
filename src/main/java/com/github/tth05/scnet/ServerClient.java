package com.github.tth05.scnet;

import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.IMessageProcessor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The server-side endpoint for an accepted client.
 */
class ServerClient extends AbstractClient {

    ServerClient(
            @NotNull SocketChannel socketChannel,
            @NotNull List<IConnectionListener> connectionListeners,
            @NotNull IMessageProcessor messageProcessor,
            @NotNull IMessageBus messageBus,
            @NotNull Consumer<ServerClient> afterClose
    ) throws IOException {
        setMessageProcessor(messageProcessor);
        setMessageBus(messageBus);
        this.connectionListeners = connectionListeners;
        installConnectedChannel(socketChannel);
        startEventLoop(createDefaultExecutor(), () -> afterClose.accept(this));
    }

    private static Executor createDefaultExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                0,
                1,
                1L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable, "SCNet Server Client");
                    thread.setDaemon(true);
                    return thread;
                }
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}
