package com.github.tth05.scnet;

import java.nio.channels.SocketChannel;

public class ServerClient extends AbstractClient {

    public ServerClient(SocketChannel socketChannel) {
        super(socketChannel);
    }
}
