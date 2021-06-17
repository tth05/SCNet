package com.github.tth05.scnet.message;

import java.util.function.Consumer;

public interface IMessageBus {

    <T extends IMessage> void listenAlways(Class<T> messageClass, Consumer<T> listener);

    <T extends IMessage> void listenOnce(Class<T> messageClass, Consumer<T> listener);

    void post(IMessage message);
}
