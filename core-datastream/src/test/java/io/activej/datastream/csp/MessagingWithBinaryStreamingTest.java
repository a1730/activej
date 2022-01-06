package io.activej.datastream.csp;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufPool;
import io.activej.common.MemSize;
import io.activej.csp.binary.ByteBufsCodec;
import io.activej.csp.net.Messaging;
import io.activej.csp.net.MessagingWithBinaryStreaming;
import io.activej.datastream.StreamSupplier;
import io.activej.net.SimpleServer;
import io.activej.net.socket.tcp.AsyncTcpSocketNio;
import io.activej.promise.Promise;
import io.activej.test.TestUtils;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.util.List;
import java.util.Objects;
import java.util.stream.LongStream;

import static io.activej.common.Utils.first;
import static io.activej.csp.binary.ByteBufsDecoder.ofNullTerminatedBytes;
import static io.activej.promise.TestUtils.await;
import static io.activej.serializer.BinarySerializers.LONG_SERIALIZER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;

public final class MessagingWithBinaryStreamingTest {
	private static final ByteBufsCodec<String, String> STRING_SERIALIZER = ByteBufsCodec
			.ofDelimiter(
					ofNullTerminatedBytes(),
					buf -> {
						ByteBuf buf1 = ByteBufPool.ensureWriteRemaining(buf, 1);
						buf1.put((byte) 0);
						return buf1;
					})
			.andThen(
					buf -> buf.asString(UTF_8),
					str -> ByteBuf.wrapForReading(str.getBytes(UTF_8)));
	private static final ByteBufsCodec<Integer, Integer> INTEGER_SERIALIZER = STRING_SERIALIZER.andThen(Integer::parseInt, n -> Integer.toString(n));

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private static void pong(Messaging<Integer, Integer> messaging) {
		messaging.receive()
				.thenIfElse(Objects::nonNull,
						msg -> messaging.send(msg).whenResult(() -> pong(messaging)),
						$ -> {
							messaging.close();
							return Promise.complete();
						})
				.whenException(e -> messaging.close());
	}

	private static void ping(int n, Messaging<Integer, Integer> messaging) {
		messaging.send(n)
				.then(messaging::receive)
				.whenResult(msg -> {
					if (msg != null) {
						if (msg > 0) {
							ping(msg - 1, messaging);
						} else {
							messaging.close();
						}
					}
				})
				.whenException(e -> messaging.close());
	}

	@Test
	public void testPing() throws Exception {
		SimpleServer server = SimpleServer.create(socket ->
						pong(MessagingWithBinaryStreaming.create(socket, INTEGER_SERIALIZER)))
				.withListenPort(0)
				.withAcceptOnce();
		server.listen();

		await(AsyncTcpSocketNio.connect(first(server.getBoundAddresses()))
				.whenComplete(TestUtils.assertCompleteFn(socket -> ping(3, MessagingWithBinaryStreaming.create(socket, INTEGER_SERIALIZER)))));
	}

	@Test
	public void testMessagingDownload() throws Exception {
		List<Long> source = LongStream.range(0, 100).boxed().collect(toList());

		SimpleServer server = SimpleServer.create(
						socket -> {
							MessagingWithBinaryStreaming<String, String> messaging =
									MessagingWithBinaryStreaming.create(socket, STRING_SERIALIZER);

							messaging.receive()
									.whenResult(msg -> {
										assertEquals("start", msg);
										StreamSupplier.ofIterable(source)
												.transformWith(ChannelSerializer.create(LONG_SERIALIZER)
														.withInitialBufferSize(MemSize.of(1)))
												.streamTo(messaging.sendBinaryStream());
									});
						})
				.withListenPort(0)
				.withAcceptOnce();
		server.listen();

		List<Long> list = await(AsyncTcpSocketNio.connect(first(server.getBoundAddresses()))
				.then(socket -> {
					MessagingWithBinaryStreaming<String, String> messaging =
							MessagingWithBinaryStreaming.create(socket, STRING_SERIALIZER);

					return messaging.send("start")
							.then(messaging::sendEndOfStream)
							.then(() -> messaging.receiveBinaryStream()
									.transformWith(ChannelDeserializer.create(LONG_SERIALIZER))
									.toList());
				}));

		assertEquals(source, list);
	}

	@Test
	public void testBinaryMessagingUpload() throws Exception {
		List<Long> source = LongStream.range(0, 100).boxed().collect(toList());

		ByteBufsCodec<String, String> serializer = STRING_SERIALIZER;

		SimpleServer server = SimpleServer.create(
						socket -> {
							MessagingWithBinaryStreaming<String, String> messaging =
									MessagingWithBinaryStreaming.create(socket, serializer);

							messaging.receive()
									.whenComplete(TestUtils.assertCompleteFn(msg -> assertEquals("start", msg)))
									.then(() ->
											messaging.receiveBinaryStream()
													.transformWith(ChannelDeserializer.create(LONG_SERIALIZER))
													.toList()
													.then(list ->
															messaging.sendEndOfStream().map($2 -> list)))
									.whenComplete(TestUtils.assertCompleteFn(list -> assertEquals(source, list)));
						})
				.withListenPort(0)
				.withAcceptOnce();
		server.listen();

		await(AsyncTcpSocketNio.connect(first(server.getBoundAddresses()))
				.whenResult(socket -> {
					MessagingWithBinaryStreaming<String, String> messaging =
							MessagingWithBinaryStreaming.create(socket, serializer);

					messaging.send("start");

					StreamSupplier.ofIterable(source)
							.transformWith(ChannelSerializer.create(LONG_SERIALIZER)
									.withInitialBufferSize(MemSize.of(1)))
							.streamTo(messaging.sendBinaryStream());
				}));
	}

	@Test
	public void testBinaryMessagingUploadAck() throws Exception {
		List<Long> source = LongStream.range(0, 100).boxed().collect(toList());

		ByteBufsCodec<String, String> serializer = STRING_SERIALIZER;

		SimpleServer server = SimpleServer.create(
						socket -> {
							MessagingWithBinaryStreaming<String, String> messaging = MessagingWithBinaryStreaming.create(socket, serializer);

							messaging.receive()
									.whenResult(msg -> assertEquals("start", msg))
									.then(msg ->
											messaging.receiveBinaryStream()
													.transformWith(ChannelDeserializer.create(LONG_SERIALIZER))
													.toList()
													.then(list ->
															messaging.send("ack")
																	.then(messaging::sendEndOfStream)
																	.map($ -> list)))
									.whenComplete(TestUtils.assertCompleteFn(list -> assertEquals(source, list)));
						})
				.withListenPort(0)
				.withAcceptOnce();
		server.listen();

		String msg = await(AsyncTcpSocketNio.connect(first(server.getBoundAddresses()))
				.then(socket -> {
					MessagingWithBinaryStreaming<String, String> messaging =
							MessagingWithBinaryStreaming.create(socket, serializer);

					return messaging.send("start")
							.then(() -> StreamSupplier.ofIterable(source)
									.transformWith(ChannelSerializer.create(LONG_SERIALIZER)
											.withInitialBufferSize(MemSize.of(1)))
									.streamTo(messaging.sendBinaryStream()))
							.then(messaging::receive)
							.whenComplete(messaging::close);
				}));

		assertEquals("ack", msg);
	}

	@Test
	public void testGsonMessagingUpload() throws Exception {
		List<Long> source = LongStream.range(0, 100).boxed().collect(toList());

		SimpleServer server = SimpleServer.create(
						socket -> {
							MessagingWithBinaryStreaming<String, String> messaging =
									MessagingWithBinaryStreaming.create(socket, STRING_SERIALIZER);

							messaging.receive()
									.whenComplete(TestUtils.assertCompleteFn(msg -> assertEquals("start", msg)))
									.then(msg -> messaging.sendEndOfStream())
									.then(msg ->
											messaging.receiveBinaryStream()
													.transformWith(ChannelDeserializer.create(LONG_SERIALIZER))
													.toList())
									.whenComplete(TestUtils.assertCompleteFn(list -> assertEquals(source, list)));
						})
				.withListenPort(0)
				.withAcceptOnce();
		server.listen();

		await(AsyncTcpSocketNio.connect(first(server.getBoundAddresses()))
				.whenResult(socket -> {
					MessagingWithBinaryStreaming<String, String> messaging =
							MessagingWithBinaryStreaming.create(socket, STRING_SERIALIZER);

					messaging.send("start");

					StreamSupplier.ofIterable(source)
							.transformWith(ChannelSerializer.create(LONG_SERIALIZER)
									.withInitialBufferSize(MemSize.of(1)))
							.streamTo(messaging.sendBinaryStream());
				}));
	}

}
