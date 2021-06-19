package com.github.tth05.scnet.message.impl;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import com.github.tth05.scnet.util.ByteBufferUtils;
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
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

public class DefaultMessageProcessor implements IMessageProcessor {

    private static final int MESSAGE_HEADER_BYTES = 6;

    private final Map<Short, RegisteredIncomingMessage> incomingMessages = new HashMap<>();
    private final Map<Class<? extends IMessage>, Short> outgoingMessages = new HashMap<>();

    private final Queue<IMessage> outgoingMessageQueue = new ConcurrentLinkedDeque<>();

    private ByteBuffer writeBuffer = ByteBuffer.allocateDirect(16384);
    private ByteBuffer messageWriteBuffer = ByteBuffer.allocate(512);
    private ByteBuffer readBuffer = ByteBuffer.allocateDirect(4096);

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
        this.outgoingMessageQueue.offer(message);
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
        for (Iterator<IMessage> iterator = this.outgoingMessageQueue.iterator(); iterator.hasNext(); ) {
            IMessage message = iterator.next();
            ByteBufferOutputStream messageOutStream = new ByteBufferOutputStream(this.messageWriteBuffer);
            message.write(messageOutStream);

            //If the buffer increased in size, save the reference
            if (this.messageWriteBuffer != messageOutStream.getBuffer()) {
                this.messageWriteBuffer = messageOutStream.getBuffer();
            }

            int size = this.messageWriteBuffer.position();
            short messageId = this.outgoingMessages.getOrDefault(message.getClass(), (short) -1);
            if (messageId == -1)
                throw new IllegalArgumentException("Message " + message.getClass() + " is not registered");

            //If the current message doesn't fit into our writeBuffer, then flush it
            if (this.writeBuffer.position() + MESSAGE_HEADER_BYTES + size > this.writeBuffer.capacity()) {
                this.writeBuffer.flip();
                while (this.writeBuffer.hasRemaining())
                    channel.write(this.writeBuffer);
                this.writeBuffer.clear();
            }

            //Append the packet to the writeBuffer
            this.writeBuffer.putShort(messageId);
            this.writeBuffer.putInt(size);
            this.messageWriteBuffer.flip();
            this.writeBuffer.put(this.messageWriteBuffer);

            iterator.remove();
        }

        this.writeBuffer.flip();
        while (this.writeBuffer.hasRemaining())
            channel.write(this.writeBuffer);
        this.writeBuffer.clear();
    }

    private boolean doRead(SocketChannel channel, IMessageBus messageBus) {
        try {
            this.readBuffer.clear();
            int bytesInBuffer = channel.read(this.readBuffer);
            if (bytesInBuffer == -1)
                return false;

            int messageStart = 0;
            while (true) {
                //Move remaining bytes to front
                if (this.readBuffer.capacity() - messageStart < MESSAGE_HEADER_BYTES) {
                    ByteBufferUtils.moveToFrontAndClear(this.readBuffer, messageStart);
                    messageStart = 0;
                    bytesInBuffer = this.readBuffer.position();
                }

                //2 bytes id, 4 bytes size
                if (bytesInBuffer - messageStart < MESSAGE_HEADER_BYTES) {
                    if (!ByteBufferUtils.readAtLeastBlocking(channel, this.readBuffer, 6))
                        return false;
                    bytesInBuffer = this.readBuffer.position();
                }

                this.readBuffer.position(messageStart);
                short id = this.readBuffer.getShort();
                int size = this.readBuffer.getInt();

                if (this.readBuffer.capacity() - messageStart < MESSAGE_HEADER_BYTES + size) { //Full message is not contained in current buffer
                    if (MESSAGE_HEADER_BYTES + size <= this.readBuffer.capacity()) { //If the buffer can hold the message, then move it to the front and read the rest
                        ByteBufferUtils.moveToFrontAndClear(this.readBuffer, messageStart);
                    } else { //If the full message can't fit into the buffer, then move it to the front in a new buffer
                        //Create new buffer which can hold the message
                        this.readBuffer.position(messageStart);
                        this.readBuffer = ByteBufferUtils.moveToNewDirectBuffer(this.readBuffer, MESSAGE_HEADER_BYTES + size);
                    }

                    messageStart = 0;
                    //Read the full message
                    if (!ByteBufferUtils.readAtLeastBlocking(channel, this.readBuffer, messageStart + MESSAGE_HEADER_BYTES + size))
                        return false;
                    bytesInBuffer = this.readBuffer.position();
                } else if (bytesInBuffer - messageStart < MESSAGE_HEADER_BYTES + size) { //Full message is not contained in buffer but fits
                    this.readBuffer.position(bytesInBuffer);
                    //Read rest of message
                    if (!ByteBufferUtils.readAtLeastBlocking(channel, this.readBuffer, messageStart + MESSAGE_HEADER_BYTES + size))
                        return false;
                    bytesInBuffer = this.readBuffer.position();
                }

                this.readBuffer.position(messageStart + MESSAGE_HEADER_BYTES);

                //Process the message
                RegisteredIncomingMessage registeredMessage = this.incomingMessages.get(id);
                if (registeredMessage != null) {
                    IMessage message = registeredMessage.newInstance();
                    message.read(new ByteBufferInputStream(this.readBuffer));
                    messageBus.post(message);
                }

                messageStart += MESSAGE_HEADER_BYTES + size;

                //If we've processed everything that's currently in the buffer, read more or return
                if (messageStart >= bytesInBuffer) {
                    this.readBuffer.rewind();
                    bytesInBuffer = channel.read(this.readBuffer);
                    messageStart = 0;

                    if (bytesInBuffer == 0)
                        break;
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

    @Override
    public void setWriteBufferSize(int size) {
        this.writeBuffer = ByteBuffer.allocateDirect(size);
    }

    @Override
    public int getWriteBufferSize() {
        return this.writeBuffer.capacity();
    }

    @Override
    public void setReadBufferSize(int size) {
        this.readBuffer = ByteBuffer.allocateDirect(size);
    }

    @Override
    public int getReadBufferSize() {
        return this.readBuffer.capacity();
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
