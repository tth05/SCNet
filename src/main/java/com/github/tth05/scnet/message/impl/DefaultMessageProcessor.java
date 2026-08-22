package com.github.tth05.scnet.message.impl;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.message.IMessageBus;
import com.github.tth05.scnet.message.IMessageProcessor;
import com.github.tth05.scnet.message.MalformedFrameException;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * A nonblocking framed message processor. Frames retain the original two-byte id and four-byte payload length header.
 */
public class DefaultMessageProcessor implements IMessageProcessor {

    private static final int MESSAGE_HEADER_BYTES = Short.BYTES + Integer.BYTES;

    /**
     * Compatibility default matching the payload range accepted by the original protocol implementation.
     * Applications accepting untrusted peers should configure a smaller limit explicitly.
     */
    public static final int DEFAULT_MAX_FRAME_SIZE = Integer.MAX_VALUE - MESSAGE_HEADER_BYTES;

    /**
     * Compatibility default matching the string range accepted by the original stream implementation.
     * Applications accepting untrusted peers should configure a smaller limit explicitly.
     */
    public static final int DEFAULT_MAX_STRING_LENGTH = Integer.MAX_VALUE - Integer.BYTES;

    /** A conservative application-level frame limit for trusted local protocols. */
    public static final int RECOMMENDED_MAX_FRAME_SIZE = 16 * 1024 * 1024;

    /** A conservative application-level string limit for trusted local protocols. */
    public static final int RECOMMENDED_MAX_STRING_LENGTH = 16 * 1024 * 1024;

    @NotNull
    private final Map<Short, RegisteredIncomingMessage> incomingMessages = new ConcurrentHashMap<>();
    @NotNull
    private final Map<Class<? extends AbstractMessage>, Short> outgoingMessages = new ConcurrentHashMap<>();
    @NotNull
    private final Map<Short, Class<? extends AbstractMessage>> registeredMessageIds = new HashMap<>();
    @NotNull
    private final Queue<AbstractMessage> outgoingMessageQueue = new ConcurrentLinkedQueue<>();

    @NotNull
    private final ByteBuffer headerBuffer = ByteBuffer.allocate(MESSAGE_HEADER_BYTES);
    @NotNull
    private ByteBuffer readChunk = ByteBuffer.allocateDirect(4096);
    @Nullable
    private ByteBuffer incomingPayload;
    private short incomingMessageId;

    @Nullable
    private ByteBuffer pendingWrite;
    @Nullable
    private volatile Selector activeSelector;
    @Nullable
    private volatile Throwable lastError;

    private volatile int processLoopDelay = 5;
    private volatile int writeBufferSize = 16384;
    private volatile int readBufferSize = 4096;
    private volatile int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
    private volatile int maxStringLength = DEFAULT_MAX_STRING_LENGTH;

    public DefaultMessageProcessor() {
        RegisteredIncomingMessage emptyMessage = new RegisteredIncomingMessage(EmptyMessage.class);
        this.incomingMessages.put((short) 0, emptyMessage);
        this.outgoingMessages.put(EmptyMessage.class, (short) 0);
        this.registeredMessageIds.put((short) 0, EmptyMessage.class);
    }

    @Override
    public <T extends AbstractMessage> void registerMessage(short id, @NotNull Class<T> messageClass) {
        registerMessageInternal(id, messageClass, null);
    }

    @Override
    public <T extends AbstractMessage> void registerMessage(
            short id,
            @NotNull Class<T> messageClass,
            @NotNull Supplier<? extends T> instanceFactory
    ) {
        registerMessageInternal(id, messageClass, Objects.requireNonNull(instanceFactory, "instanceFactory"));
    }

    private synchronized <T extends AbstractMessage> void registerMessageInternal(
            short id,
            @NotNull Class<T> messageClass,
            @Nullable Supplier<? extends T> instanceFactory
    ) {
        Objects.requireNonNull(messageClass, "messageClass");
        if (id < 1) {
            throw new IllegalArgumentException("id has to be greater than zero");
        }
        if (this.registeredMessageIds.containsKey(id)) {
            throw new IllegalArgumentException("message with id " + id + " is already registered");
        }

        boolean incoming = !AbstractMessageOutgoing.class.isAssignableFrom(messageClass);
        boolean outgoing = !AbstractMessageIncoming.class.isAssignableFrom(messageClass);
        if (!incoming && !outgoing) {
            throw new IllegalArgumentException("message class cannot be both incoming-only and outgoing-only");
        }
        if (outgoing && this.outgoingMessages.containsKey(messageClass)) {
            throw new IllegalArgumentException("outgoing message class " + messageClass.getName() + " is already registered");
        }

        RegisteredIncomingMessage registeredIncoming = incoming
                ? newIncomingMessage(messageClass, instanceFactory)
                : null;

        if (registeredIncoming != null) {
            this.incomingMessages.put(id, registeredIncoming);
        }
        if (outgoing) {
            this.outgoingMessages.put(messageClass, id);
        }
        this.registeredMessageIds.put(id, messageClass);
    }

    private static RegisteredIncomingMessage newIncomingMessage(
            @NotNull Class<? extends AbstractMessage> messageClass,
            @Nullable Supplier<? extends AbstractMessage> instanceFactory
    ) {
        return instanceFactory == null
                ? new RegisteredIncomingMessage(messageClass)
                : new RegisteredIncomingMessage(messageClass, instanceFactory);
    }

    @Override
    public void enqueueMessage(@NotNull AbstractMessage message) {
        this.outgoingMessageQueue.offer(Objects.requireNonNull(message, "message"));
        Selector selector = this.activeSelector;
        if (selector != null) {
            selector.wakeup();
        }
    }

    @Override
    public boolean process(@NotNull Selector selector, @NotNull SocketChannel channel, @NotNull IMessageBus messageBus) {
        Objects.requireNonNull(selector, "selector");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(messageBus, "messageBus");
        this.activeSelector = selector;
        this.lastError = null;

        try {
            updateWriteInterest(selector, channel);
            selector.select();

            for (Iterator<SelectionKey> iterator = selector.selectedKeys().iterator(); iterator.hasNext(); ) {
                SelectionKey key = iterator.next();
                iterator.remove();
                if (!key.isValid() || key.channel() != channel) {
                    continue;
                }
                if (key.isReadable() && !readAvailable(channel, messageBus)) {
                    return false;
                }
                if (key.isValid() && key.isWritable()) {
                    writeAvailable(channel);
                }
            }

            updateWriteInterest(selector, channel);
            return true;
        } catch (ClosedSelectorException | ClosedChannelException e) {
            return false;
        } catch (Throwable t) {
            if (channel.isOpen() && selector.isOpen()) {
                this.lastError = t;
            }
            return false;
        }
    }

    @Override
    @Nullable
    public Throwable getLastError() {
        return this.lastError;
    }

    private void updateWriteInterest(Selector selector, SocketChannel channel) throws ClosedChannelException {
        SelectionKey key = channel.keyFor(selector);
        if (key == null) {
            channel.register(selector, SelectionKey.OP_READ);
            key = channel.keyFor(selector);
        }
        if (key == null || !key.isValid()) {
            throw new ClosedChannelException();
        }

        int desiredOps = SelectionKey.OP_READ;
        if (this.pendingWrite != null || !this.outgoingMessageQueue.isEmpty()) {
            desiredOps |= SelectionKey.OP_WRITE;
        }
        if (key.interestOps() != desiredOps) {
            key.interestOps(desiredOps);
        }
    }

    private void writeAvailable(SocketChannel channel) throws IOException {
        while (true) {
            if (this.pendingWrite == null) {
                this.pendingWrite = serializeNextFrame();
                if (this.pendingWrite == null) {
                    return;
                }
            }

            int written = channel.write(this.pendingWrite);
            if (written == 0 || this.pendingWrite.hasRemaining()) {
                return;
            }
            this.pendingWrite = null;
        }
    }

    @Nullable
    private ByteBuffer serializeNextFrame() throws IOException {
        AbstractMessage message = this.outgoingMessageQueue.poll();
        if (message == null) {
            return null;
        }

        Short messageId = this.outgoingMessages.get(message.getClass());
        if (messageId == null) {
            throw new IllegalArgumentException("Message " + message.getClass().getName() + " is not registered");
        }

        int initialSize = Math.min(this.writeBufferSize, this.maxFrameSize);
        ByteBufferOutputStream messageStream = new ByteBufferOutputStream(
                initialSize,
                this.maxFrameSize,
                this.maxStringLength
        );
        try {
            message.write(messageStream);
        } catch (Throwable t) {
            throw new MalformedFrameException("Unable to serialize message " + message.getClass().getName(), t);
        }

        ByteBuffer payload = messageStream.getBuffer();
        int size = payload.position();
        ByteBuffer frame = ByteBuffer.allocateDirect(MESSAGE_HEADER_BYTES + size);
        frame.putShort(messageId);
        frame.putInt(size);
        payload.flip();
        frame.put(payload);
        frame.flip();
        return frame;
    }

    private boolean readAvailable(SocketChannel channel, IMessageBus messageBus) throws IOException {
        if (this.readChunk.capacity() != this.readBufferSize) {
            this.readChunk = ByteBuffer.allocateDirect(this.readBufferSize);
        }

        while (true) {
            this.readChunk.clear();
            int bytesRead = channel.read(this.readChunk);
            if (bytesRead == -1) {
                return false;
            }
            if (bytesRead == 0) {
                return true;
            }

            this.readChunk.flip();
            consumeReadChunk(this.readChunk, messageBus);
        }
    }

    private void consumeReadChunk(ByteBuffer source, IMessageBus messageBus) throws IOException {
        while (source.hasRemaining()) {
            if (this.incomingPayload == null) {
                transfer(source, this.headerBuffer);
                if (this.headerBuffer.hasRemaining()) {
                    return;
                }

                this.headerBuffer.flip();
                this.incomingMessageId = this.headerBuffer.getShort();
                int payloadSize = this.headerBuffer.getInt();
                this.headerBuffer.clear();
                if (payloadSize < 0) {
                    throw new MalformedFrameException("Negative frame payload length: " + payloadSize);
                }
                if (payloadSize > this.maxFrameSize) {
                    throw new MalformedFrameException(
                            "Frame payload length " + payloadSize + " exceeds maximum " + this.maxFrameSize
                    );
                }

                this.incomingPayload = ByteBuffer.allocate(payloadSize);
                if (payloadSize == 0) {
                    dispatchIncomingMessage(messageBus);
                }
            }

            if (this.incomingPayload != null) {
                transfer(source, this.incomingPayload);
                if (!this.incomingPayload.hasRemaining()) {
                    dispatchIncomingMessage(messageBus);
                }
            }
        }
    }

    private void dispatchIncomingMessage(IMessageBus messageBus) throws IOException {
        ByteBuffer payload = this.incomingPayload;
        this.incomingPayload = null;
        if (payload == null) {
            return;
        }
        payload.flip();

        RegisteredIncomingMessage registeredMessage = this.incomingMessages.get(this.incomingMessageId);
        if (registeredMessage == null) {
            return;
        }

        AbstractMessage message;
        try {
            message = registeredMessage.newInstance();
            message.read(new ByteBufferInputStream(payload.asReadOnlyBuffer(), this.maxStringLength));
        } catch (Throwable t) {
            throw new MalformedFrameException(
                    "Unable to deserialize message " + registeredMessage.messageClass.getName(),
                    t
            );
        }
        messageBus.post(message);
    }

    private static void transfer(ByteBuffer source, ByteBuffer destination) {
        int bytesToCopy = Math.min(source.remaining(), destination.remaining());
        int oldLimit = source.limit();
        source.limit(source.position() + bytesToCopy);
        destination.put(source);
        source.limit(oldLimit);
    }

    @Override
    public void reset() {
        this.activeSelector = null;
        this.lastError = null;
        this.outgoingMessageQueue.clear();
        this.pendingWrite = null;
        this.headerBuffer.clear();
        this.incomingPayload = null;
        this.readChunk = ByteBuffer.allocateDirect(this.readBufferSize);
    }

    /**
     * Retained for source compatibility. The selector now blocks until I/O or an enqueue wakeup, so this value is not
     * used as a polling delay.
     */
    @Override
    public void setProcessLoopDelay(int processLoopDelay) {
        if (processLoopDelay < 0) {
            throw new IllegalArgumentException("processLoopDelay cannot be negative");
        }
        this.processLoopDelay = processLoopDelay;
    }

    @Override
    public int getProcessLoopDelay() {
        return this.processLoopDelay;
    }

    @Override
    public void setWriteBufferSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("write buffer size must be positive");
        }
        this.writeBufferSize = size;
    }

    @Override
    public int getWriteBufferSize() {
        return this.writeBufferSize;
    }

    @Override
    public void setReadBufferSize(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("read buffer size must be positive");
        }
        this.readBufferSize = size;
        Selector selector = this.activeSelector;
        if (selector != null) {
            selector.wakeup();
        }
    }

    @Override
    public int getReadBufferSize() {
        return this.readBufferSize;
    }

    @Override
    public void setMaxFrameSize(int size) {
        if (size < 0 || size > Integer.MAX_VALUE - MESSAGE_HEADER_BYTES) {
            throw new IllegalArgumentException("Invalid maximum frame size: " + size);
        }
        this.maxFrameSize = size;
    }

    @Override
    public int getMaxFrameSize() {
        return this.maxFrameSize;
    }

    @Override
    public void setMaxStringLength(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("Maximum string length cannot be negative");
        }
        this.maxStringLength = size;
    }

    @Override
    public int getMaxStringLength() {
        return this.maxStringLength;
    }

    private static final class RegisteredIncomingMessage {

        @NotNull
        private final Class<? extends AbstractMessage> messageClass;
        @NotNull
        private final Supplier<? extends AbstractMessage> instanceSupplier;

        private RegisteredIncomingMessage(@NotNull Class<? extends AbstractMessage> messageClass) {
            this.messageClass = messageClass;
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                MethodHandle constructorHandle = lookup.findConstructor(messageClass, MethodType.methodType(void.class));
                //noinspection unchecked
                this.instanceSupplier = (Supplier<? extends AbstractMessage>) LambdaMetafactory.metafactory(
                        lookup,
                        "get",
                        MethodType.methodType(Supplier.class),
                        constructorHandle.type().generic(),
                        constructorHandle,
                        constructorHandle.type()
                ).getTarget().invokeExact();
            } catch (Throwable e) {
                throw new IllegalArgumentException(
                        "Unable to create constructor factory. Make sure a public default constructor exists",
                        e
                );
            }
        }

        private RegisteredIncomingMessage(
                @NotNull Class<? extends AbstractMessage> messageClass,
                @NotNull Supplier<? extends AbstractMessage> instanceSupplier
        ) {
            this.messageClass = messageClass;
            this.instanceSupplier = () -> messageClass.cast(
                    Objects.requireNonNull(instanceSupplier.get(), "instanceFactory returned null")
            );
        }

        @NotNull
        @Contract(value = "-> new", pure = true)
        private AbstractMessage newInstance() {
            return this.instanceSupplier.get();
        }
    }
}
