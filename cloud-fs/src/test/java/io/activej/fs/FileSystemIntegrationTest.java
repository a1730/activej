package io.activej.fs;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufs;
import io.activej.common.exception.TruncatedDataException;
import io.activej.common.exception.UnexpectedDataException;
import io.activej.common.tuple.Tuple2;
import io.activej.csp.consumer.ChannelConsumers;
import io.activej.csp.file.ChannelFileWriter;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.fs.adapter.FileSystemAdapters;
import io.activej.fs.exception.FileSystemException;
import io.activej.fs.exception.FileSystemIOException;
import io.activej.fs.exception.ForbiddenPathException;
import io.activej.fs.tcp.FileSystemServer;
import io.activej.fs.tcp.RemoteFileSystem;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static io.activej.bytebuf.ByteBufStrings.wrapUtf8;
import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static io.activej.test.TestUtils.getFreePort;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.*;

public final class FileSystemIntegrationTest {
	private static final byte[] BIG_FILE = new byte[2 * 1024 * 1024]; // 2 MB
	private static final byte[] CONTENT = "content".getBytes(UTF_8);

	static {
		ThreadLocalRandom.current().nextBytes(BIG_FILE);
	}

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	private Path storage;
	private FileSystemServer server;
	private IFileSystem fileSystem;

	@Before
	public void setup() throws IOException {
		InetSocketAddress address = new InetSocketAddress("localhost", getFreePort());
		Executor executor = newCachedThreadPool();

		storage = temporaryFolder.newFolder("server_storage").toPath();
		FileSystem fs = FileSystem.create(Reactor.getCurrentReactor(), executor, storage);
		await(fs.start());
		server = FileSystemServer.builder(Reactor.getCurrentReactor(), fs)
			.withListenAddress(address)
			.build();
		server.listen();
		fileSystem = RemoteFileSystem.create(Reactor.getCurrentReactor(), address);
	}

	@Test
	public void testUpload() throws IOException {
		String resultFile = "file_uploaded.txt";

		await(upload(resultFile, CONTENT)
			.whenComplete(server::close));

		assertArrayEquals(CONTENT, Files.readAllBytes(storage.resolve(resultFile)));
	}

	@Test
	public void testUploadCompletesCorrectly() {
		String resultFile = "file_uploaded.txt";

		ByteBuf value = ByteBuf.wrapForReading(CONTENT);
		byte[] bytes = await(fileSystem.upload(resultFile)
			.then(ChannelSuppliers.ofValue(value)::streamTo)
			.map($ -> {
				try {
					return Files.readAllBytes(storage.resolve(resultFile));
				} catch (IOException e) {
					throw new AssertionError(e);
				}
			})
			.whenComplete(server::close));

		assertArrayEquals(CONTENT, bytes);
	}

	@Test
	public void uploadLessThanSpecified() {
		String filename = "incomplete.txt";
		Path path = storage.resolve(filename);
		assertFalse(Files.exists(path));

		ByteBuf value = wrapUtf8("data");
		Exception exception = awaitException(ChannelSuppliers.ofValue(value)
			.streamTo(fileSystem.upload(filename, 10))
			.whenComplete(server::close));

		assertThat(exception, instanceOf(TruncatedDataException.class));

		assertFalse(Files.exists(path));
	}

	@Test
	public void uploadMoreThanSpecified() {
		String filename = "incomplete.txt";
		Path path = storage.resolve(filename);
		assertFalse(Files.exists(path));

		ByteBuf value = wrapUtf8("data data data data");
		Exception exception = awaitException(ChannelSuppliers.ofValue(value)
			.streamTo(fileSystem.upload(filename, 10))
			.whenComplete(server::close));

		assertThat(exception, instanceOf(UnexpectedDataException.class));

		assertFalse(Files.exists(path));
	}

	@Test
	public void testUploadMultiple() throws IOException {
		int files = 10;

		await(Promises.all(IntStream.range(0, 10)
				.mapToObj(i -> {
					ByteBuf value = ByteBuf.wrapForReading(CONTENT);
					return ChannelSuppliers.ofValue(value)
						.streamTo(ChannelConsumers.ofPromise(fileSystem.upload("file" + i, CONTENT.length)));
				}))
			.whenComplete(server::close));

		for (int i = 0; i < files; i++) {
			assertArrayEquals(CONTENT, Files.readAllBytes(storage.resolve("file" + i)));
		}
	}

	@Test
	public void testUploadBigFile() throws IOException {
		String resultFile = "big file_uploaded.txt";

		await(upload(resultFile, BIG_FILE)
			.whenComplete(server::close));

		assertArrayEquals(BIG_FILE, Files.readAllBytes(storage.resolve(resultFile)));
	}

	@Test
	public void testUploadLong() throws IOException {
		String resultFile = "this/is/not/empty/directory/2/file2_uploaded.txt";

		await(upload(resultFile, CONTENT)
			.whenComplete(server::close));

		assertArrayEquals(CONTENT, Files.readAllBytes(storage.resolve(resultFile)));
	}

	@Test
	public void testUploadServerFail() {
		Exception exception = awaitException(upload("../../nonlocal/../file.txt", CONTENT)
			.whenComplete(server::close));

		assertThat(exception, instanceOf(ForbiddenPathException.class));
	}

	@Test
	public void testOnClientExceptionWhileUploading() {
		String resultFile = "upload_with_exceptions.txt";

		ByteBuf value = wrapUtf8("Test4");
		ByteBuf value1 = ByteBuf.wrapForReading(BIG_FILE);
		ChannelSupplier<ByteBuf> supplier = ChannelSuppliers.concat(
			ChannelSuppliers.ofValues(wrapUtf8("Test1"), wrapUtf8(" Test2"), wrapUtf8(" Test3")).async(),
			ChannelSuppliers.ofValue(value1),
			ChannelSuppliers.ofException(new FileSystemIOException("Test exception")),
			ChannelSuppliers.ofValue(value));

		Exception exception = awaitException(supplier.streamTo(ChannelConsumers.ofPromise(fileSystem.upload(resultFile, Long.MAX_VALUE)))
			.whenComplete(server::close));

		assertThat(exception, instanceOf(FileSystemException.class));
		assertThat(exception.getMessage(), containsString("Test exception"));

		assertFalse(Files.exists(storage.resolve(resultFile)));
	}

	private Promise<ByteBuf> download(String file) {
		return fileSystem.download(file)
			.then(supplier -> supplier.toCollector(ByteBufs.collector()))
			.whenComplete(server::close);
	}

	@Test
	public void testDownload() throws Exception {
		String file = "file1_downloaded.txt";
		Files.write(storage.resolve(file), CONTENT);

		ByteBuf result = await(download(file));

		assertArrayEquals(CONTENT, result.asArray());
	}

	@Test
	public void testDownloadLong() throws Exception {
		String file = "this/is/not/empty/directory/file.txt";
		Files.createDirectories(storage.resolve("this/is/not/empty/directory"));
		Files.write(storage.resolve(file), CONTENT);

		ByteBuf result = await(download(file));

		assertArrayEquals(CONTENT, result.asArray());
	}

	@Test
	public void testDownloadNotExist() {
		String file = "file_not_exist_downloaded.txt";
		Exception exception = awaitException(ChannelSuppliers.ofPromise(fileSystem.download(file))
			.streamTo(ChannelConsumers.ofAsyncConsumer($ -> Promise.complete()))
			.whenComplete(server::close));

		assertThat(exception, instanceOf(FileSystemException.class));
		assertThat(exception.getMessage(), containsString("File not found"));
	}

	@Test
	public void testManySimultaneousDownloads() throws IOException {
		String file = "some_file.txt";
		Files.write(storage.resolve(file), CONTENT);

		List<Promise<Void>> tasks = new ArrayList<>();

		Executor executor = newCachedThreadPool();
		for (int i = 0; i < 10; i++) {
			tasks.add(ChannelSuppliers.ofPromise(fileSystem.download(file))
				.streamTo(ChannelFileWriter.open(executor, storage.resolve("file" + i))));
		}

		await(Promises.all(tasks)
			.whenComplete(server::close));

		for (int i = 0; i < tasks.size(); i++) {
			assertArrayEquals(CONTENT, Files.readAllBytes(storage.resolve("file" + i)));
		}
	}

	@Test
	public void testDeleteFile() throws Exception {
		String file = "file.txt";
		Files.write(storage.resolve(file), CONTENT);

		await(fileSystem.delete(file)
			.whenComplete(server::close));

		assertFalse(Files.exists(storage.resolve(file)));
	}

	@Test
	public void testDeleteMissingFile() {
		String file = "no_file.txt";

		FileMetadata metadata = await(fileSystem.delete(file)
			.then(() -> fileSystem.info(file))
			.whenComplete(server::close));
		assertNull(metadata);
	}

	@Test
	public void testFileList() throws Exception {
		Set<String> expected = Set.of("this/is/not/empty/directory/file1.txt", "file1.txt", "first file.txt");

		Files.createDirectories(storage.resolve("this/is/not/empty/directory/"));
		for (String filename : expected) {
			Files.write(storage.resolve(filename), CONTENT);
		}

		Map<String, FileMetadata> metadataMap = await(fileSystem.list("**")
			.whenComplete(server::close));

		assertEquals(expected, metadataMap.keySet());
	}

	@Test
	public void testSubdirectoryClient() throws IOException {
		Files.createDirectories(storage.resolve("this/is/not/empty/directory/"));
		Files.createDirectories(storage.resolve("subdirectory1/"));
		Files.createDirectories(storage.resolve("subdirectory2/subsubdirectory"));
		Files.write(storage.resolve("this/is/not/empty/directory/file1.txt"), CONTENT);
		Files.write(storage.resolve("this/is/not/empty/directory/file1.txt"), CONTENT);
		Files.write(storage.resolve("file1.txt"), CONTENT);
		Files.write(storage.resolve("first file.txt"), CONTENT);
		Files.write(storage.resolve("subdirectory1/file1.txt"), CONTENT);
		Files.write(storage.resolve("subdirectory1/first file.txt"), CONTENT);
		Files.write(storage.resolve("subdirectory2/file1.txt"), CONTENT);
		Files.write(storage.resolve("subdirectory2/first file.txt"), CONTENT);
		Files.write(storage.resolve("subdirectory2/subsubdirectory/file1.txt"), CONTENT);
		Files.write(storage.resolve("subdirectory2/subsubdirectory/first file.txt"), CONTENT);

		Set<String> expected1 = new HashSet<>();
		expected1.add("file1.txt");
		expected1.add("first file.txt");

		Set<String> expected2 = new HashSet<>(expected1);
		expected2.add("subsubdirectory/file1.txt");
		expected2.add("subsubdirectory/first file.txt");

		Tuple2<Map<String, FileMetadata>, Map<String, FileMetadata>> tuple = await(
			Promises.toTuple(FileSystemAdapters.subdirectory(fileSystem, "subdirectory1").list("**"), FileSystemAdapters.subdirectory(fileSystem, "subdirectory2").list("**"))
				.whenComplete(server::close)
		);

		assertEquals(expected1, tuple.value1().keySet());
		assertEquals(expected2, tuple.value2().keySet());
	}

	@Test
	public void testAppend() throws IOException {
		String filename = "file.txt";
		int offset = 3;
		String contentString = new String(CONTENT, UTF_8);
		String toAppend = "appended";
		String appended = contentString.substring(offset) + toAppend;
		Files.write(storage.resolve(filename), CONTENT);

		ByteBuf value = wrapUtf8(appended);
		String result = await(ChannelSuppliers.ofValue(value)
			.streamTo(fileSystem.append(filename, offset))
			.then(() -> fileSystem.download(filename))
			.then(supplier -> supplier.toCollector(ByteBufs.collector())
				.map(byteBuf -> byteBuf.asString(UTF_8)))
			.whenComplete(server::close));

		assertEquals(contentString + toAppend, result);
	}

	private Promise<Void> upload(String resultFile, byte[] bytes) {
		ByteBuf value = ByteBuf.wrapForReading(bytes);
		return fileSystem.upload(resultFile, bytes.length)
			.then(ChannelSuppliers.ofValue(value)::streamTo);
	}
}
