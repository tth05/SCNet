package com.github.tth05.scnet.message;

import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public interface IMessageProcessor {

    <T extends IMessage> void registerMessage(short id, Class<T> messageClass);

    void enqueueMessage(IMessage message);

    boolean process(Selector selector, SocketChannel channel, IMessageBus messageBus);
}
