package io.activej.http;

import io.activej.bytebuf.ByteBufPool;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.function.RunnableEx;
import io.activej.common.ref.Ref;
import io.activej.common.ref.RefBoolean;
import io.activej.common.ref.RefInt;
import io.activej.csp.consumer.ChannelConsumers;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.dns.DnsClient;
import io.activej.http.IWebSocket.Message;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.SettablePromise;
import io.activej.reactor.Reactor;
import io.activej.reactor.nio.NioReactor;
import io.activej.test.rules.ActivePromisesRule;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.activej.http.HttpUtils.inetAddress;
import static io.activej.http.WebSocketConstants.HANDSHAKE_FAILED;
import static io.activej.https.SslUtils.createTestSslContext;
import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static io.activej.test.TestUtils.getFreePort;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;

public final class WebSocketClientServerTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final ActivePromisesRule activePromisesRule = new ActivePromisesRule();

	private int port;
	private DnsClient dnsClient;

	@Before
	public void setUp() {
		port = getFreePort();
		dnsClient = DnsClient.create(Reactor.getCurrentReactor(), inetAddress("8.8.8.8"));
	}

	@Test
	public void testEcho() throws IOException {
		startTestServer(ws -> ws.messageReadChannel().streamTo(ws.messageWriteChannel()));

		Stream<String> inputStream = IntStream.range(0, 100).mapToObj(String::valueOf);

		String result = await(HttpClient.create(Reactor.getCurrentReactor(), dnsClient)
			.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build())
			.then(ws -> {
				ChannelSuppliers.ofStream(inputStream)
					.mapAsync(item -> Promises.delay(1L, Message.text(item)))
					.streamTo(ws.messageWriteChannel());
				return ws.messageReadChannel()
					.map(Message::getText)
					.toCollector(toList())
					.map(strings -> String.join("", strings));
			}));

		assertEquals(IntStream.range(0, 100).mapToObj(String::valueOf).collect(joining()), result);
	}

	@Test
	public void testServerWSException() throws IOException {
		RefInt counter = new RefInt(100);
		List<Message> messages = new ArrayList<>();
		String reason = "Some error";
		WebSocketException exception = new WebSocketException(4321, reason);

		startTestServer(webSocket -> ChannelSuppliers.ofAsyncSupplier(() -> Promise.of("hello"))
			.mapAsync(
				buf -> {
					if (counter.dec() < 0) {
						return Promise.ofException(exception);
					} else {
						return Promise.of(Message.text(buf));
					}
				})
			.streamTo(webSocket.messageWriteChannel()));

		Exception receivedEx = awaitException(HttpClient.create(Reactor.getCurrentReactor(), dnsClient)
			.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build())
			.then(webSocket -> webSocket.messageReadChannel().streamTo(ChannelConsumers.ofConsumer(messages::add))));

		assertThat(receivedEx, instanceOf(WebSocketException.class));
		assertEquals(Integer.valueOf(4321), ((WebSocketException) receivedEx).getCode());
		assertEquals(reason, ((WebSocketException) receivedEx).getReason());

		assertEquals(100, messages.size());
		for (Message msg : messages) {
			assertEquals("hello", msg.getText());
		}
	}

	@Test
	public void testSecureWebSocketsCloseByClient() throws IOException {
		WebSocketException testError = new WebSocketException(4321, "Test error");
		ExecutorService executor = Executors.newSingleThreadExecutor();
		SettablePromise<WebSocketException> settablePromise = new SettablePromise<>();

		startSecureTestServer(webSocket -> webSocket.readFrame()
			.whenException(settablePromise::setException));

		await(HttpClient.builder(Reactor.getCurrentReactor(), dnsClient)
			.withSslEnabled(createTestSslContext(), executor)
			.build()
			.webSocketRequest(HttpRequest.get("wss://127.0.0.1:" + port).build())
			.whenResult(webSocket -> webSocket.closeEx(testError)));

		WebSocketException exception = awaitException(settablePromise);

		assertEquals(testError.getCode(), exception.getCode());
		assertEquals(testError.getMessage(), exception.getMessage());
		executor.shutdown();
	}

	@Test
	public void testSecureWebSocketsCloseByServer() throws IOException {
		WebSocketException testError = new WebSocketException(4321, "Test error");
		ExecutorService executor = Executors.newSingleThreadExecutor();

		startSecureTestServer(webSocket -> webSocket.closeEx(testError));

		WebSocketException exception = awaitException(HttpClient.builder(Reactor.getCurrentReactor(), dnsClient)
			.withSslEnabled(createTestSslContext(), executor)
			.build()
			.webSocketRequest(HttpRequest.get("wss://127.0.0.1:" + port).build())
			.then(webSocket -> webSocket.messageReadChannel()
				.streamTo(ChannelConsumers.ofConsumer($ -> fail()))));

		assertEquals(testError.getCode(), exception.getCode());
		assertEquals(testError.getMessage(), exception.getMessage());
		executor.shutdown();
	}

	@Test
	public void testRejectedHandshake() throws IOException {
		NioReactor reactor = Reactor.getCurrentReactor();
		HttpServer.builder(reactor, RoutingServlet.builder(reactor)
				.withWebSocket("/", new WebSocketServlet(reactor) {
					@Override
					protected Promise<HttpResponse> onRequest(HttpRequest request) {
						return HttpResponse.ofCode(400).withBody(ByteBufPool.allocate(1000)).toPromise();
					}

					@Override
					protected void onWebSocket(IWebSocket webSocket) {
					}
				})
				.build())
			.withListenPort(port)
			.withAcceptOnce()
			.build()
			.listen();
		Exception exception = awaitException(HttpClient.create(reactor, dnsClient)
			.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build()));

		assertEquals(HANDSHAKE_FAILED, exception);
	}

	@Test
	public void testRejectedWithException() throws IOException {
		NioReactor reactor = Reactor.getCurrentReactor();
		HttpServer.builder(reactor, RoutingServlet.builder(reactor)
				.withWebSocket("/", new WebSocketServlet(reactor) {
					@Override
					protected Promise<HttpResponse> onRequest(HttpRequest request) {
						return Promise.ofException(new MalformedDataException());
					}

					@Override
					protected void onWebSocket(IWebSocket webSocket) {
					}
				})
				.build())
			.withListenPort(port)
			.withAcceptOnce()
			.build()
			.listen();
		Exception exception = awaitException(HttpClient.create(reactor, dnsClient)
			.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build()));

		assertEquals(HANDSHAKE_FAILED, exception);
	}

	@Test
	public void testCloseByServerWithError() throws IOException {
		WebSocketException testError = new WebSocketException(4321, "Test error");
		ExecutorService executor = Executors.newSingleThreadExecutor();
		List<String> messages = List.of("first", "second", "third");

		startTestServer(webSocket -> webSocket.writeMessage(Message.text(messages.get(0)))
			.then(() -> webSocket.writeMessage(Message.text(messages.get(1))))
			.then(() -> webSocket.writeMessage(Message.text(messages.get(2))))
			.whenComplete(() -> webSocket.closeEx(testError)));

		List<String> result = new ArrayList<>();
		WebSocketException exception = awaitException(HttpClient.builder(Reactor.getCurrentReactor(), dnsClient)
			.withSslEnabled(createTestSslContext(), executor)
			.build()
			.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build())
			.then(webSocket -> webSocket.readMessage()
				.then(message -> {
					result.add(message.getText());
					return webSocket.readMessage();
				})
				.then(message -> {
					result.add(message.getText());
					return webSocket.readMessage();
				})
				.then(message -> {
					result.add(message.getText());
					return webSocket.readMessage();
				})));

		assertEquals(testError.getCode(), exception.getCode());
		assertEquals(testError.getMessage(), exception.getMessage());
		assertEquals(result, messages);
		executor.shutdown();
	}

	@Test
	public void testCloseByServerWithEOS() throws IOException {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		List<String> messages = List.of("first", "second", "third");

		startTestServer(webSocket -> webSocket.writeMessage(Message.text(messages.get(0)))
			.then(() -> webSocket.writeMessage(Message.text(messages.get(1))))
			.then(() -> webSocket.writeMessage(Message.text(messages.get(2))))
			.then(() -> webSocket.writeMessage(null))
			.whenException((RunnableEx) Assert::fail)
		);

		List<String> result = new ArrayList<>();
		Message lastMessage = await(HttpClient.builder(Reactor.getCurrentReactor(), dnsClient)
			.withSslEnabled(createTestSslContext(), executor)
			.build()
			.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build())
			.then(webSocket -> webSocket.readMessage()
				.then(message -> {
					result.add(message.getText());
					return webSocket.readMessage();
				})
				.then(message -> {
					result.add(message.getText());
					return webSocket.readMessage();
				})
				.then(message -> {
					result.add(message.getText());
					return webSocket.readMessage();
				})));

		assertNull(lastMessage);
		assertEquals(result, messages);
		executor.shutdown();
	}

	@Test
	public void testCloseByClientWithError() throws IOException {
		WebSocketException testError = new WebSocketException(4321, "Test error");
		ExecutorService executor = Executors.newSingleThreadExecutor();
		List<String> messages = List.of("first", "second", "third");
		List<String> result = new ArrayList<>();
		Ref<Exception> serverErrorRef = new Ref<>();

		startTestServer(webSocket -> webSocket.readMessage()
			.then(message -> {
				result.add(message.getText());
				return webSocket.readMessage();
			})
			.then(message -> {
				result.add(message.getText());
				return webSocket.readMessage();
			})
			.then(message -> {
				result.add(message.getText());
				return webSocket.readMessage();
			})
			.whenException(serverErrorRef::set));

		await(HttpClient.builder(Reactor.getCurrentReactor(), dnsClient)
			.withSslEnabled(createTestSslContext(), executor)
			.build()
			.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build())
			.then(webSocket -> webSocket.writeMessage(Message.text(messages.get(0)))
				.then(() -> webSocket.writeMessage(Message.text(messages.get(1))))
				.then(() -> webSocket.writeMessage(Message.text(messages.get(2))))
				.whenComplete(() -> webSocket.closeEx(testError))));

		WebSocketException exception = (WebSocketException) serverErrorRef.get();
		assertEquals(testError.getCode(), exception.getCode());
		assertEquals(testError.getMessage(), exception.getMessage());
		assertEquals(result, messages);
		executor.shutdown();
	}

	@Test
	public void testCloseByClientWithEOS() throws IOException {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		List<String> messages = List.of("first", "second", "third");
		List<String> result = new ArrayList<>();

		RefBoolean lastMessageNull = new RefBoolean(false);
		startTestServer(webSocket -> webSocket.readMessage()
			.then(message -> {
				result.add(message.getText());
				return webSocket.readMessage();
			})
			.then(message -> {
				result.add(message.getText());
				return webSocket.readMessage();
			})
			.then(message -> {
				result.add(message.getText());
				return webSocket.readMessage();
			})
			.whenResult(lastMessage -> {
				if (lastMessage == null) {
					lastMessageNull.set(true);
				}
			}));

		await(HttpClient.builder(Reactor.getCurrentReactor(), dnsClient)
			.withSslEnabled(createTestSslContext(), executor)
			.build()
			.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build())
			.then(webSocket -> webSocket.writeMessage(Message.text(messages.get(0)))
				.then(() -> webSocket.writeMessage(Message.text(messages.get(1))))
				.then(() -> webSocket.writeMessage(Message.text(messages.get(2))))
				.then(() -> webSocket.writeMessage(null))
				.whenException((RunnableEx) Assert::fail)
			));

		assertTrue(lastMessageNull.get());
		assertEquals(result, messages);
		executor.shutdown();
	}

	@Test
	public void testNonWebSocketServlet() throws IOException {
		NioReactor reactor = Reactor.getCurrentReactor();
		HttpServer.builder(reactor, RoutingServlet.builder(reactor)
				.with("/", $ -> HttpResponse.ok200().toPromise())
				.build())
			.withListenPort(port)
			.withAcceptOnce()
			.build()
			.listen();

		Exception exception = awaitException(HttpClient.create(reactor, dnsClient)
			.webSocketRequest(HttpRequest.get("ws://127.0.0.1:" + port).build()));

		assertEquals(HANDSHAKE_FAILED, exception);
	}

	@Test
	public void testNonWebSocketClient() throws IOException {
		NioReactor reactor = Reactor.getCurrentReactor();
		HttpServer.builder(reactor, RoutingServlet.builder(reactor)
				.withWebSocket("/", ws -> fail())
				.build())
			.withListenPort(port)
			.withAcceptOnce()
			.build()
			.listen();

		int responseCode = await(HttpClient.create(reactor, dnsClient)
			.request(HttpRequest.get("http://127.0.0.1:" + port).build())
			.map(HttpResponse::getCode));

		assertEquals(404, responseCode);
	}

	private void startTestServer(Consumer<IWebSocket> webSocketConsumer) throws IOException {
		NioReactor reactor = Reactor.getCurrentReactor();
		HttpServer.builder(reactor, RoutingServlet.builder(reactor)
				.withWebSocket("/", webSocketConsumer)
				.build())
			.withListenPort(port)
			.withAcceptOnce()
			.build()
			.listen();
	}

	private void startSecureTestServer(Consumer<IWebSocket> webSocketConsumer) throws IOException {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		NioReactor reactor = Reactor.getCurrentReactor();
		HttpServer server = HttpServer.builder(reactor, RoutingServlet.builder(reactor)
				.withWebSocket("/", webSocketConsumer)
				.build())
			.withSslListenPort(createTestSslContext(), executor, port)
			.withAcceptOnce()
			.build();
		server.getCloseNotification().whenComplete(executor::shutdown);
		server.listen();
	}

}
