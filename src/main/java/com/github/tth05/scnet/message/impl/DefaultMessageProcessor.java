package com.github.tth05.scnet.message.impl;

import com.github.tth05.scnet.ByteBufferUtils;
import com.github.tth05.scnet.message.*;

import java.io.IOException;
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

    private static final int MESSAGE_HEADER_BYTES = 6;

    private final Map<Short, RegisteredIncomingMessage> incomingMessages = new HashMap<>();
    private final Map<Class<? extends IMessage>, Short> outgoingMessages = new HashMap<>();

    private final Queue<IMessage> outgoingMessageQueue = new ArrayDeque<>();

    private ByteBuffer writeBuffer = ByteBuffer.allocateDirect(100);
    private ByteBuffer messageBuffer = ByteBuffer.allocateDirect(1000);

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
                    doWrite(channel);
                }
                if (key.isReadable()) {
                    if (!doRead(channel, messageBus))
                        return false;
                }

                iterator.remove();
            }

            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    private void doWrite(SocketChannel channel) throws IOException {
        synchronized (this.outgoingMessageQueue) {
            for (IMessage message : this.outgoingMessageQueue) {
                this.writeBuffer.clear();
                this.writeBuffer.position(MESSAGE_HEADER_BYTES);
                message.write(this.writeBuffer);

                int size = this.writeBuffer.position() - MESSAGE_HEADER_BYTES;
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

    private boolean doRead(SocketChannel channel, IMessageBus messageBus) {
        try {
            this.messageBuffer.clear();
            int bytesInBuffer = channel.read(this.messageBuffer);

            int messageStart = 0;
            while (true) {
                //Move remaining bytes to front
                if (this.messageBuffer.capacity() - messageStart < MESSAGE_HEADER_BYTES) {
                    byte[] tmp = new byte[this.messageBuffer.capacity() - messageStart];
                    this.messageBuffer.position(messageStart);
                    this.messageBuffer.get(tmp);
                    this.messageBuffer.clear();
                    this.messageBuffer.put(tmp);
                    messageStart = 0;
                    bytesInBuffer = tmp.length;
                }

                //2 bytes id, 4 bytes size
                if (bytesInBuffer - messageStart < MESSAGE_HEADER_BYTES) {
                    if (!ByteBufferUtils.readAtLeastBlocking(channel, this.messageBuffer, 6))
                        return false;
                    bytesInBuffer = this.messageBuffer.position();
                }

                this.messageBuffer.position(messageStart);
                short id = this.messageBuffer.getShort();
                int size = this.messageBuffer.getInt();

                if (this.messageBuffer.capacity() - messageStart < MESSAGE_HEADER_BYTES + size) { //Message does not fit in current buffer
                    //Create new buffer which can hold the message
                    ByteBuffer old = this.messageBuffer;
                    old.position(0);
                    this.messageBuffer = ByteBuffer.allocateDirect(messageStart + MESSAGE_HEADER_BYTES + size);
                    this.messageBuffer.put(old);
                    //Read the full message
                    if (!ByteBufferUtils.readAtLeastLimitBlocking(channel, this.messageBuffer))
                        return false;
                    bytesInBuffer = this.messageBuffer.position();
                } else if (bytesInBuffer - messageStart < MESSAGE_HEADER_BYTES + size) { //Full message is not contained in buffer but fits
                    this.messageBuffer.position(bytesInBuffer);
                    //Read rest of message
                    if (!ByteBufferUtils.readAtLeastBlocking(channel, this.messageBuffer, messageStart + MESSAGE_HEADER_BYTES + size))
                        return false;
                    bytesInBuffer = this.messageBuffer.position();
                }

                this.messageBuffer.position(messageStart + MESSAGE_HEADER_BYTES);

                RegisteredIncomingMessage registeredMessage = this.incomingMessages.get(id);
                if (registeredMessage != null) {
                    IMessage message = registeredMessage.newInstance();
                    message.read(this.messageBuffer);
                    messageBus.post(message);
                }

                messageStart += MESSAGE_HEADER_BYTES + size;

                if (messageStart >= bytesInBuffer) {
                    this.messageBuffer.position(0);
                    bytesInBuffer = channel.read(this.messageBuffer);
                    messageStart = 0;
                    if (bytesInBuffer == 0) {
                        break;
                    }
                    if (bytesInBuffer == -1)
                        return false;
                }
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

    private static final class RegisteredIncomingMessage {

        private final Supplier<? extends IMessage> instanceSupplier;

        private RegisteredIncomingMessage(Class<? extends IMessage> messageClass) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodHandle constructorHandle = lookup.findConstructor(messageClass, MethodType.methodType(void.class));
                //noinspection unchecked
                this.instanceSupplier = (Supplier<? extends IMessage>) LambdaMetafactory.metafactory(
                        lookup,
                        "get", MethodType.methodType(Supplier.class),
                        constructorHandle.type().generic(), constructorHandle, constructorHandle.type()
                ).getTarget().invokeExact();
            } catch (Throwable e) {
                throw new IllegalArgumentException("Unable to create lambda factory for constructor", e);
            }
        }

        public IMessage newInstance() {
            return this.instanceSupplier.get();
        }
    }
}
