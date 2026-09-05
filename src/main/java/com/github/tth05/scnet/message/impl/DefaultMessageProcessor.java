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

import java.io.EOFException;
import java.io.IOException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * A nonblocking framed message processor. Frames retain the original two-byte id and four-byte payload length header.
 */
public class DefaultMessageProcessor implements IMessageProcessor {

    private static final int MESSAGE_HEADER_BYTES = Short.BYTES + Integer.BYTES;

    /** Default payload limit, configurable before connecting. */
    public static final int DEFAULT_MAX_FRAME_SIZE = ByteBufferOutputStream.DEFAULT_MAX_CAPACITY;

    /** Default UTF-8 string limit; the enclosing payload limit also applies. */
    public static final int DEFAULT_MAX_STRING_LENGTH = ByteBufferInputStream.DEFAULT_MAX_STRING_BYTES;

    /** Includes the frame currently being serialized or written. */
    public static final int DEFAULT_MAX_PENDING_MESSAGES = 1024;

    @NotNull
    private final Map<Short, RegisteredIncomingMessage> incomingMessages = new ConcurrentHashMap<>();
    @NotNull
    private final Map<Class<? extends AbstractMessage>, Short> outgoingMessages = new ConcurrentHashMap<>();
    @NotNull
    private final Map<Short, Class<? extends AbstractMessage>> registeredMessageIds = new HashMap<>();
    @NotNull
    private final Queue<AbstractMessage> outgoingMessageQueue = new ConcurrentLinkedQueue<>();
    private final Object outboundStateLock = new Object();

    private boolean acceptingOutboundMessages = true;
    private int pendingMessageCount;
    private volatile int maxPendingMessages = DEFAULT_MAX_PENDING_MESSAGES;
    @Nullable
    private volatile RejectedExecutionException outboundFailure;
    @Nullable
    private CompletableFuture<Void> outboundDrain;

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

    private volatile int writeBufferSize = 16384;
    private volatile int readBufferSize = 4096;
    private volatile int maxFrameSize = DEFAULT_MAX_FRAME_SIZE;
    private volatile int maxStringLength = DEFAULT_MAX_STRING_LENGTH;

    public DefaultMessageProcessor() {
        RegisteredIncomingMessage emptyMessage = new RegisteredIncomingMessage(EmptyMessage.class, EmptyMessage::new);
        this.incomingMessages.put((short) 0, emptyMessage);
        this.outgoingMessages.put(EmptyMessage.class, (short) 0);
        this.registeredMessageIds.put((short) 0, EmptyMessage.class);
    }

    @Override
    public <T extends AbstractMessageOutgoing> void registerMessage(short id, @NotNull Class<T> messageClass) {
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

        if (incoming && instanceFactory == null) {
            throw new IllegalArgumentException("incoming message registration requires an instance factory");
        }
        RegisteredIncomingMessage registeredIncoming = incoming
                ? new RegisteredIncomingMessage(messageClass, instanceFactory)
                : null;

        if (registeredIncoming != null) {
            this.incomingMessages.put(id, registeredIncoming);
        }
        if (outgoing) {
            this.outgoingMessages.put(messageClass, id);
        }
        this.registeredMessageIds.put(id, messageClass);
    }

    @Override
    public void enqueueMessage(@NotNull AbstractMessage message) {
        Objects.requireNonNull(message, "message");
        RejectedExecutionException failure = null;
        synchronized (this.outboundStateLock) {
            checkOutboundFailure();
            if (!this.acceptingOutboundMessages) {
                throw new RejectedExecutionException("The connection is draining pending outbound messages");
            }
            if (this.pendingMessageCount >= this.maxPendingMessages) {
                failure = new RejectedExecutionException("Outbound queue exceeded " + this.maxPendingMessages
                        + " pending messages; closing the connection because the receiver is not keeping up");
                this.outboundFailure = failure;
                this.acceptingOutboundMessages = false;
            } else {
                this.outgoingMessageQueue.offer(message);
                this.pendingMessageCount++;
            }
        }
        wakeActiveSelector();
        if (failure != null) {
            throw failure;
        }
    }

    private void checkOutboundFailure() {
        RejectedExecutionException failure = this.outboundFailure;
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public CompletionStage<Void> beginOutboundDrain() {
        CompletableFuture<Void> drain;
        synchronized (this.outboundStateLock) {
            if (this.outboundDrain == null) {
                this.acceptingOutboundMessages = false;
                this.outboundDrain = new CompletableFuture<>();
            }
            drain = this.outboundDrain;
        }
        wakeActiveSelector();
        return drain;
    }

    private void wakeActiveSelector() {
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
            checkOutboundFailure();
            if (completeOutboundDrainIfReady()) {
                return false;
            }
            updateWriteInterest(selector, channel);
            selector.select();
            checkOutboundFailure();

            for (Iterator<SelectionKey> iterator = selector.selectedKeys().iterator(); iterator.hasNext(); ) {
                SelectionKey key = iterator.next();
                iterator.remove();
                if (!key.isValid() || key.channel() != channel) {
                    continue;
                }
                if (key.isReadable() && !readAvailable(channel, messageBus)) {
                    failOutboundDrain(new EOFException("Peer closed before pending outbound messages were drained"));
                    return false;
                }
                if (key.isValid() && key.isWritable()) {
                    writeAvailable(channel);
                }
            }

            if (completeOutboundDrainIfReady()) {
                return false;
            }
            updateWriteInterest(selector, channel);
            return true;
        } catch (ClosedSelectorException | ClosedChannelException e) {
            failOutboundDrain(e);
            return false;
        } catch (Throwable t) {
            if (channel.isOpen() && selector.isOpen()) {
                this.lastError = t;
            }
            failOutboundDrain(t);
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
            checkOutboundFailure();
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
            synchronized (this.outboundStateLock) {
                this.pendingMessageCount--;
            }
        }
    }

    private boolean completeOutboundDrainIfReady() {
        CompletableFuture<Void> drain;
        synchronized (this.outboundStateLock) {
            drain = this.outboundDrain;
            if (drain == null || this.pendingWrite != null || !this.outgoingMessageQueue.isEmpty()) {
                return false;
            }
        }
        drain.complete(null);
        return true;
    }

    private void failOutboundDrain(Throwable cause) {
        CompletableFuture<Void> drain;
        synchronized (this.outboundStateLock) {
            drain = this.outboundDrain;
        }
        if (drain != null) {
            drain.completeExceptionally(cause);
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
            checkOutboundFailure();
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
        CompletableFuture<Void> unfinishedDrain;
        synchronized (this.outboundStateLock) {
            unfinishedDrain = this.outboundDrain;
            this.outboundDrain = null;
            this.acceptingOutboundMessages = true;
            this.outboundFailure = null;
            this.pendingMessageCount = 0;
            this.outgoingMessageQueue.clear();
        }
        this.activeSelector = null;
        this.lastError = null;
        this.pendingWrite = null;
        this.headerBuffer.clear();
        this.incomingPayload = null;
        this.readChunk = ByteBuffer.allocateDirect(this.readBufferSize);
        if (unfinishedDrain != null && !unfinishedDrain.isDone()) {
            unfinishedDrain.completeExceptionally(new ClosedChannelException());
        }
    }

    @Override
    public void setMaxPendingMessages(int count) {
        synchronized (this.outboundStateLock) {
            if (count < 1 || count < this.pendingMessageCount) {
                throw new IllegalArgumentException("Pending message limit must be positive and at least the current pending count");
            }
            this.maxPendingMessages = count;
        }
    }

    @Override
    public int getMaxPendingMessages() {
        return this.maxPendingMessages;
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
