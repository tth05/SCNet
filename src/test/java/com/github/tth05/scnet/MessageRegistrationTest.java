package com.github.tth05.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class MessageRegistrationTest {

    @Test
    public void rejectsAnOutgoingIdAlreadyUsedByAnIncomingMessage() {
        DefaultMessageProcessor processor = new DefaultMessageProcessor();
        processor.registerMessage((short) 1, IncomingMessage.class, IncomingMessage::new);

        assertThrows(
                IllegalArgumentException.class,
                () -> processor.registerMessage((short) 1, OutgoingMessage.class)
        );
    }

    @Test
    public void rejectsRegisteringOneOutgoingClassUnderTwoIds() {
        DefaultMessageProcessor processor = new DefaultMessageProcessor();
        processor.registerMessage((short) 1, OutgoingMessage.class);

        assertThrows(
                IllegalArgumentException.class,
                () -> processor.registerMessage((short) 2, OutgoingMessage.class)
        );
    }

    @Test
    void explicitDirectionsShareTheIdNamespaceWithLegacyRegistration() {
        DefaultMessageProcessor processor = new DefaultMessageProcessor();
        processor.registerIncoming((short) 1, SharedMessage.class, SharedMessage::new);
        assertThrows(IllegalArgumentException.class,
                () -> processor.registerOutgoing((short) 1, SharedMessage.class));
        assertThrows(IllegalArgumentException.class,
                () -> processor.registerMessage((short) 1, OutgoingMessage.class));
        processor.registerOutgoing((short) 2, SharedMessage.class);
        assertThrows(IllegalArgumentException.class,
                () -> processor.registerBidirectional((short) 3, SharedMessage.class, SharedMessage::new));
    }

    @Test
    void explicitDirectionsRejectInvalidIdsFactoriesAndIncompatibleClasses() {
        DefaultMessageProcessor processor = new DefaultMessageProcessor();
        assertThrows(IllegalArgumentException.class,
                () -> processor.registerOutgoing((short) 0, SharedMessage.class));
        assertThrows(IllegalArgumentException.class,
                () -> processor.registerIncoming((short) -1, SharedMessage.class, SharedMessage::new));
        assertThrows(NullPointerException.class,
                () -> processor.registerIncoming((short) 1, SharedMessage.class, null));
        assertThrows(NullPointerException.class,
                () -> processor.registerBidirectional((short) 1, SharedMessage.class, null));
        assertThrows(IllegalArgumentException.class,
                () -> processor.registerOutgoing((short) 1, IncomingMessage.class));
        assertThrows(IllegalArgumentException.class,
                () -> processor.registerIncoming((short) 1, OutgoingMessage.class, OutgoingMessage::new));
        assertThrows(IllegalArgumentException.class,
                () -> processor.registerBidirectional((short) 1, IncomingMessage.class, IncomingMessage::new));
        assertThrows(IllegalArgumentException.class,
                () -> processor.registerBidirectional((short) 1, OutgoingMessage.class, OutgoingMessage::new));
        // Failed registrations must not reserve the ID.
        processor.registerBidirectional((short) 1, SharedMessage.class, SharedMessage::new);
    }

    public static final class SharedMessage extends AbstractMessage {
        @Override public void read(@NotNull ByteBufferInputStream input) { }
        @Override public void write(@NotNull ByteBufferOutputStream output) { }
    }

    public static final class IncomingMessage extends AbstractMessageIncoming {

        @Override
        public void read(@NotNull ByteBufferInputStream messageStream) {
        }
    }

    public static final class OutgoingMessage extends AbstractMessageOutgoing {

        @Override
        public void write(@NotNull ByteBufferOutputStream messageStream) {
        }
    }
}
