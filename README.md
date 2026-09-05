# SCNet

SCNet connects one client and one server with typed messages over Java NIO socket channels. It requires Java 21. The server accepts one active client and rejects additional connections.

## Build and dependency

Use the checked-in wrapper:

```powershell
.\gradlew.bat clean build publishToMavenLocal '-PscnetVersion=2.0.0' --warning-mode fail
```

The current coordinated development build uses `com.github.tth05:SCNet:2.0.0`. Public release publication is still pending. Consumers of published versions use Packagecloud:

```groovy
repositories {
    maven { url = uri('https://packagecloud.io/tth05/repo/maven2') }
}
dependencies {
    implementation 'com.github.tth05:SCNet:2.0.0'
}
```

For local coordinated builds, explicitly add Maven Local to the consumer's repositories. A public consumer must resolve the selected version without that local repository.

## Example

Register incoming factories explicitly. The same message ID must have the same payload layout on both sides. Outgoing-only messages can use the two-argument registration overload.

```java
import com.github.tth05.scnet.Client;
import com.github.tth05.scnet.Server;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class Example {
    public static final class TextMessage extends AbstractMessage {
        private String text;
        public TextMessage() {}
        public TextMessage(String text) { this.text = text; }
        @Override public void read(ByteBufferInputStream input) { this.text = input.readString(); }
        @Override public void write(ByteBufferOutputStream output) { output.writeString(this.text); }
    }

    public static void main(String[] args) throws Exception {
        try (Server server = new Server(); Client client = new Client()) {
            server.getMessageProcessor().registerMessage((short) 1, TextMessage.class, TextMessage::new);
            client.getMessageProcessor().registerMessage((short) 1, TextMessage.class, TextMessage::new);
            CountDownLatch received = new CountDownLatch(1);
            server.getMessageBus().listenOnce(TextMessage.class, message -> {
                System.out.println(message.text);
                received.countDown();
            });
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            client.connect(server.getLocalAddress());
            client.getMessageProcessor().enqueueMessage(new TextMessage("Hello"));
            if (!received.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Message was not received");
        }
    }
}
```

## Transport and lifecycle

Frames contain a two-byte ID, a four-byte payload length and the payload. UTF-8 strings have a four-byte byte-length prefix. Default frame payloads, strings and standalone output buffers are capped at 16 MiB. Configure smaller or larger protocol-specific limits before connecting with `setMaxFrameSize` and `setMaxStringLength`, or explicit stream constructors. The payload limit includes string prefixes and other fields.

The selector blocks until socket readiness or an enqueue wakeup; there is no polling-delay setting. A partially written frame stays pending until the socket becomes writable. Serialization failures close the transport and are reported through connection error callbacks; messages are not replayed.

At most 1024 accepted messages may await complete transmission, including the frame currently being serialized or written. Configure this count before connecting with `setMaxPendingMessages`. Overflow throws `RejectedExecutionException` to the producer and fails the connection through its normal error callback. Producers never wait for queue capacity, and reset discards pending messages before another connection starts. This bounds retained message count; it does not measure the heap size of arbitrary object graphs referenced by messages. Per-frame payload limits still apply when serializing.

`close()` closes immediately. `closeAfterPendingWrites()` on Client, or `closeClientAfterPendingWrites()` on Server, rejects new outbound messages and completes after queued bytes are written. It does not acknowledge remote application processing.

Reconnect waits for the previous transport to finish outside the client monitor. Reconnect from its active connected/message callback is rejected because that callback cannot wait for its own transport to finish. Server processor and bus configuration cannot change while a client is connected or being prepared.

Message dispatch snapshots listeners before invoking them. Registration and removal affect the next dispatch, including nested dispatch. One-shot listeners are claimed before callbacks run. Concurrent posts may invoke persistent listeners concurrently. Callback exceptions are reported to stderr and do not skip later callbacks.

Windows and Linux push/PR builds run the library tests and generate publication metadata. The library does not provide authentication, encryption or an application protocol; the consuming applications own those contracts.
