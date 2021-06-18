package com.github.tth05.scnet.message.impl;

import com.github.tth05.scnet.message.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;

public class DefaultMessageProcessor implements IMessageProcessor {

    private final Map<Short, Class<? extends IMessage>> incomingMessages = new HashMap<>();
    private final Map<Class<? extends IMessage>, Short> outgoingMessages = new HashMap<>();

    private final Queue<IMessage> outgoingMessageQueue = new ArrayDeque<>();

    private final ByteBuf writeBuffer = Unpooled.directBuffer(100);
    private final ByteBuffer messageHeaderBuffer = ByteBuffer.allocateDirect(6);

    private int processLoopDelay = 5;

    public DefaultMessageProcessor() {
        //Register noop message
        this.incomingMessages.put((short) 0, EmptyMessage.class);
        this.outgoingMessages.put(EmptyMessage.class, (short) 0);
    }

    @Override
    public <T extends IMessage> void registerMessage(short id, Class<T> messageClass) {
        if (id < 1)
            throw new IllegalArgumentException("id has to be greater than zero");

        if (IMessageIncoming.class.isAssignableFrom(messageClass)) {
            this.incomingMessages.put(id, messageClass);
        } else if (IMessageOutgoing.class.isAssignableFrom(messageClass)) {
            this.outgoingMessages.put(messageClass, id);
        } else if (IMessage.class.isAssignableFrom(messageClass)) {
            this.incomingMessages.put(id, messageClass);
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
                            this.writeBuffer.setIndex(0, 6);
                            message.write(this.writeBuffer);

                            int size = this.writeBuffer.writerIndex() - 6;
                            int messageId = this.outgoingMessages.get(message.getClass());

                            this.writeBuffer.writerIndex(0);
                            this.writeBuffer.writeShort(messageId);
                            this.writeBuffer.writeInt(size);
                            this.writeBuffer.writerIndex(this.writeBuffer.writerIndex() + size);

                            ByteBuffer nioBuffer = this.writeBuffer.nioBuffer();
                            while (nioBuffer.hasRemaining())
                                channel.write(nioBuffer);
                        }

                        this.outgoingMessageQueue.clear();
                    }

                }
                if (key.isReadable()) {
                    this.messageHeaderBuffer.clear();
                    //2 bytes id, 4 bytes size
                    int bytesRead = channel.read(this.messageHeaderBuffer);

                    while (bytesRead > 0 || this.messageHeaderBuffer.position() < 6) {
                        bytesRead = channel.read(this.messageHeaderBuffer);
                        if (bytesRead == -1)
                            return false;
                    }

                    this.messageHeaderBuffer.flip();
                    short id = this.messageHeaderBuffer.getShort();
                    int size = this.messageHeaderBuffer.getInt();

                    ByteBuffer messageBuffer = ByteBuffer.allocateDirect(size);

                    bytesRead = channel.read(messageBuffer);
                    while (bytesRead > 0 || messageBuffer.position() < size) {
                        bytesRead = channel.read(messageBuffer);
                        if (bytesRead == -1)
                            return false;
                    }

                    messageBuffer.flip();

                    Class<? extends IMessage> messageClass = this.incomingMessages.get(id);
                    if (messageClass != null) {
                        try {
                            IMessage message = messageClass.getConstructor().newInstance();
                            message.read(Unpooled.wrappedBuffer(messageBuffer));
                            messageBus.post(message);
                        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException ignored) {
                            ignored.printStackTrace();
                        }
                    }
                }

                iterator.remove();
            }

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void setProcessLoopDelay(int processLoopDelay) {
        this.processLoopDelay = processLoopDelay;
    }

    public int getProcessLoopDelay() {
        return processLoopDelay;
    }
}
