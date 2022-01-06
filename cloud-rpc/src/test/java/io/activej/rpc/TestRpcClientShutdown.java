package io.activej.rpc;

import io.activej.async.exception.AsyncCloseException;
import io.activej.eventloop.Eventloop;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.rpc.client.RpcClient;
import io.activej.rpc.client.RpcClientConnection;
import io.activej.rpc.server.RpcServer;
import io.activej.test.ExpectedException;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.ClassBuilderConstantsRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static io.activej.common.Utils.first;
import static io.activej.promise.TestUtils.awaitException;
import static io.activej.rpc.client.sender.RpcStrategies.server;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;

public final class TestRpcClientShutdown {
	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@Rule
	public final ClassBuilderConstantsRule classBuilderConstantsRule = new ClassBuilderConstantsRule();

	@Test
	public void testServerOnClientShutdown() throws IOException {
		Eventloop eventloop = Eventloop.getCurrentEventloop();
		Executor executor = Executors.newSingleThreadExecutor();
		List<Class<?>> messageTypes = asList(Request.class, Response.class);

		RpcServer rpcServer = RpcServer.create(eventloop)
				.withMessageTypes(messageTypes)
				.withHandler(Request.class,
						request -> Promise.ofBlocking(executor, () -> {
							Thread.sleep(100);
							return new Response();
						}))
				.withListenPort(0);

		rpcServer.listen();

		RpcClient rpcClient = RpcClient.create(eventloop)
				.withMessageTypes(messageTypes)
				.withStrategy(server(first(rpcServer.getBoundAddresses())));


		Exception exception = awaitException(rpcClient.start()
				.then(() -> Promises.all(
						rpcClient.sendRequest(new Request())
								.whenComplete(() -> {
									for (RpcClientConnection conn : rpcClient.getRequestStatsPerConnection().values()) {
										conn.onSenderError(new ExpectedException());
									}
								}),
						rpcClient.sendRequest(new Request()),
						rpcClient.sendRequest(new Request()),
						rpcClient.sendRequest(new Request()),
						rpcClient.sendRequest(new Request())
				))
				.whenComplete(rpcClient::stop)
				.whenComplete(rpcServer::close)
		);

		assertThat(exception, instanceOf(AsyncCloseException.class));
	}

	public static final class Request {
	}

	public static final class Response {
	}
}
