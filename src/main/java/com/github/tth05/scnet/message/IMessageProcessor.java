package com.github.tth05.scnet.message;

public interface IMessageProcessor {

    <T extends IMessage> void registerMessage(int id, Class<T> messageClass);

    void queueMessage(IMessage message);

    void processQueues();
}
