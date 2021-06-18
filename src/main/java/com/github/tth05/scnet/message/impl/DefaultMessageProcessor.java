package com.github.tth05.scnet.message.impl;

import com.github.tth05.scnet.message.*;

import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;
import java.util.function.Supplier;

public class DefaultMessageProcessor implements IMessageProcessor {

    private final Map<Short, RegisteredIncomingMessage> incomingMessages = new HashMap<>();
    private final Map<Class<? extends IMessage>, Short> outgoingMessages = new HashMap<>();

    private final Queue<IMessage> outgoingMessageQueue = new ArrayDeque<>();

    private ByteBuffer writeBuffer = ByteBuffer.allocateDirect(100);
    private ByteBuffer messageBuffer = ByteBuffer.allocateDirect(100);

    private int processLoopDelay = 5;

    public DefaultMessageProcessor() {
        //Register noop message
        this.incomingMessages.put((short) 0, new RegisteredIncomingMessage(EmptyMessage.class));
        this.outgoingMessages.put(EmptyMessage.class, (short) 0);
    }

    @Override
    public <T extends IMessage> void registerMessage(short id, Class<T> messageClass) {
        if (id < 1)
            throw new IllegalArgumentException("id has to be greater than zero");

        if (IMessageIncoming.class.isAssignableFrom(messageClass)) {
            this.incomingMessages.put(id, new RegisteredIncomingMessage(messageClass));
        } else if (IMessageOutgoing.class.isAssignableFrom(messageClass)) {
            this.outgoingMessages.put(messageClass, id);
        } else if (IMessage.class.isAssignableFrom(messageClass)) {
            this.incomingMessages.put(id, new RegisteredIncomingMessage(messageClass));
            this.outgoingMessages.put(messageClass, id);
        } else {
            throw new IllegalArgumentException("messageClass does not implement IMessage");
        }
    }

    @Override
    public void enqueueMessage(IMessage message) {
        synchronized (this.outgoingMessageQueue) {
            if (this.outgoingMessages.get(message.getClass()) == null)
                throw new IllegalArgumentException("Message not registered");

            this.outgoingMessageQueue.offer(message);
        }
    }

    @Override
    public boolean process(Selector selector, SocketChannel channel, IMessageBus messageBus) {
        try {
            Thread.sleep(this.processLoopDelay);

            int selected = selector.select(5);
            if (selected < 1)
                return true;

            for (Iterator<SelectionKey> iterator = selector.selectedKeys().iterator(); iterator.hasNext(); ) {
                SelectionKey key = iterator.next();

                if (key.isWritable() && !this.outgoingMessageQueue.isEmpty()) {
                    synchronized (this.outgoingMessageQueue) {
                        for (IMessage message : this.outgoingMessageQueue) {
                            this.writeBuffer.clear();
                            this.writeBuffer.position(6);
                            message.write(this.writeBuffer);

                            int size = this.writeBuffer.position() - 6;
                            short messageId = this.outgoingMessages.get(message.getClass());

                            this.writeBuffer.position(0);
                            this.writeBuffer.putShort(messageId);
                            this.writeBuffer.putInt(size);
                            this.writeBuffer.position(this.writeBuffer.position() + size);

                            this.writeBuffer.flip();
                            while (this.writeBuffer.hasRemaining())
                                channel.write(this.writeBuffer);
                        }

                        this.outgoingMessageQueue.clear();
                    }

                }
                if (key.isReadable()) {
                    this.messageBuffer.clear();
                    //2 bytes id, 4 bytes size
                    this.messageBuffer.limit(6);
                    int bytesRead = channel.read(this.messageBuffer);

                    while (bytesRead > 0 || this.messageBuffer.position() < 6) {
                        bytesRead = channel.read(this.messageBuffer);
                        if (bytesRead == -1)
                            return false;
                    }

                    this.messageBuffer.flip();
                    short id = this.messageBuffer.getShort();
                    int size = this.messageBuffer.getInt();

                    if (this.messageBuffer.capacity() < size) {
                        this.messageBuffer = ByteBuffer.allocateDirect(size);
                    }

                    this.messageBuffer.clear();
                    this.messageBuffer.limit(size);

                    bytesRead = channel.read(messageBuffer);
                    while (bytesRead > 0 || messageBuffer.position() < size) {
                        bytesRead = channel.read(messageBuffer);
                        if (bytesRead == -1)
                            return false;
                    }

                    this.messageBuffer.flip();

                    RegisteredIncomingMessage registeredMessage = this.incomingMessages.get(id);
                    if (registeredMessage != null) {
                        IMessage message = registeredMessage.newInstance();
                        message.read(this.messageBuffer);
                        messageBus.post(message);
                    }
                }

                iterator.remove();
            }

            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    public void setProcessLoopDelay(int processLoopDelay) {
        this.processLoopDelay = processLoopDelay;
    }

    public int getProcessLoopDelay() {
        return processLoopDelay;
    }

    private static final class RegisteredIncomingMessage {

        private final Class<? extends IMessage> messageClass;
        private final Supplier<? extends IMessage> instanceSupplier;

        private RegisteredIncomingMessage(Class<? extends IMessage> messageClass) {
            this.messageClass = messageClass;
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodHandle constructorHandle = lookup.findConstructor(messageClass, MethodType.methodType(void.class));
                this.instanceSupplier = (Supplier<? extends IMessage>) LambdaMetafactory.metafactory(
                        lookup, "get", MethodType.methodType(Supplier.class),
                        constructorHandle.type().generic(), constructorHandle, constructorHandle.type()
                ).getTarget().invokeExact();
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }

        public IMessage newInstance() {
            return this.instanceSupplier.get();
        }
    }
}
