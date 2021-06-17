package com.github.tth05.scnet;

import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class Client extends AbstractClient {

    private final Executor executor;

    public Client() {
        this(Executors.newSingleThreadExecutor());
    }

    public Client(Executor executor) {
        super();
        this.executor = executor;
    }

    public boolean connect(SocketAddress address) {
        try {
            this.close();

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

                key.cancel();
                iterator.remove();
            }

            if (!this.socketChannel.finishConnect())
                return false;

            if (this.socketChannel.isConnected()) {
                this.executor.execute(() -> {
                    while (true) {
                        readAndWrite();
                    }
                });
                return true;
            }

            return false;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
