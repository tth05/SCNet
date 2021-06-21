package com.github.tth05.scnet;

import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.IMessageProcessor;

import java.nio.channels.SocketChannel;

class ServerClient extends AbstractClient {

    public ServerClient(SocketChannel socketChannel, IMessageProcessor messageProcessor, IMessageBus messageBus) {
        super(socketChannel);
        setMessageProcessor(messageProcessor);
        setMessageBus(messageBus);
    }
}
