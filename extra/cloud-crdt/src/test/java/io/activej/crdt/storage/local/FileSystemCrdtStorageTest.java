package io.activej.crdt.storage.local;

import io.activej.crdt.CrdtData;
import io.activej.crdt.CrdtTombstone;
import io.activej.crdt.function.CrdtFunction;
import io.activej.crdt.util.CrdtDataBinarySerializer;
import io.activej.datastream.supplier.StreamSupplier;
import io.activej.datastream.supplier.StreamSuppliers;
import io.activej.fs.FileMetadata;
import io.activej.fs.FileSystem;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static io.activej.common.collection.CollectionUtils.first;
import static io.activej.common.collection.CollectorUtils.entriesToLinkedHashMap;
import static io.activej.crdt.function.CrdtFunction.ignoringTimestamp;
import static io.activej.promise.TestUtils.await;
import static io.activej.reactor.Reactor.getCurrentReactor;
import static io.activej.serializer.BinarySerializers.*;
import static org.junit.Assert.*;

public final class FileSystemCrdtStorageTest {
	private static final CrdtFunction<Set<Integer>> CRDT_FUNCTION = ignoringTimestamp(FileSystemCrdtStorageTest::union);
	private static final CrdtDataBinarySerializer<String, Set<Integer>> SERIALIZER = new CrdtDataBinarySerializer<>(UTF8_SERIALIZER, ofSet(INT_SERIALIZER));

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	private FileSystem fileSystem;
	private FileSystemCrdtStorage<String, Set<Integer>> client;

	@Before
	public void setup() throws IOException {
		Reactor reactor = getCurrentReactor();
		fileSystem = FileSystem.create(reactor, Executors.newCachedThreadPool(), temporaryFolder.newFolder().toPath());
		client = FileSystemCrdtStorage.create(reactor, fileSystem, SERIALIZER, CRDT_FUNCTION);
		await(fileSystem.start());
		await(client.start());
	}

	@Test
	public void testEmptyUpload() {
		await(StreamSuppliers.<CrdtData<String, Set<Integer>>>empty().streamTo(client.upload()));
		assertTrue(await(fileSystem.list("**")).isEmpty());
	}

	@Test
	public void testEmptyRemove() {
		await(StreamSuppliers.<CrdtTombstone<String>>empty().streamTo(client.remove()));
		assertTrue(await(fileSystem.list("**")).isEmpty());
	}

	@Test
	public void testEmptyConsolidation() {
		await(client.consolidate());
		assertTrue(await(fileSystem.list("**")).isEmpty());
	}

	@Test
	public void testConsolidation() {
		long timestamp = getCurrentReactor().currentTimeMillis();

		List<CrdtData<String, Set<Integer>>> expected = List.of(
			new CrdtData<>("12_test_1", timestamp, Set.of(123, 124, 125, 2, 542)),
			new CrdtData<>("12_test_2", timestamp, Set.of(12, 13)),
			new CrdtData<>("1_test_1", timestamp, Set.of(1, 2, 3)),
			new CrdtData<>("1_test_2", timestamp, Set.of(2, 3, 7)),
			new CrdtData<>("1_test_3", timestamp, Set.of(78, 2, 3)),
			new CrdtData<>("2_test_1", timestamp, Set.of(1, 2, 3)),
			new CrdtData<>("2_test_2", timestamp, Set.of(2, 3, 4)),
			new CrdtData<>("2_test_3", timestamp, Set.of(0, 1, 2))
		);

		await(StreamSuppliers.ofStream(Stream.of(
				new CrdtData<>("1_test_1", timestamp, Set.of(1, 2, 3)),
				new CrdtData<>("1_test_2", timestamp, Set.of(2, 3, 7)),
				new CrdtData<>("1_test_3", timestamp, Set.of(78, 2, 3)),
				new CrdtData<>("12_test_1", timestamp, Set.of(123, 124, 125)),
				new CrdtData<>("12_test_2", timestamp, Set.of(12))).sorted())
			.streamTo(client.upload()));
		await(StreamSuppliers.ofStream(Stream.of(
				new CrdtData<>("2_test_1", timestamp, Set.of(1, 2, 3)),
				new CrdtData<>("2_test_2", timestamp, Set.of(2, 3, 4)),
				new CrdtData<>("2_test_3", timestamp, Set.of(0, 1, 2)),
				new CrdtData<>("12_test_1", timestamp, Set.of(123, 542, 125, 2)),
				new CrdtData<>("12_test_2", timestamp, Set.of(12, 13))).sorted())
			.streamTo(client.upload()));

		Map<String, FileMetadata> filesBefore = await(fileSystem.list("**"));
		System.out.println(filesBefore);
		assertEquals(2, filesBefore.size());

		List<CrdtData<String, Set<Integer>>> downloadedBefore = await(client.download().then(StreamSupplier::toList));
		assertEquals(expected, downloadedBefore);

		await(client.consolidate());

		Map<String, FileMetadata> filesAfter = await(fileSystem.list("**"));
		System.out.println(filesAfter);
		assertEquals(1, filesAfter.size());
		assertFalse(filesBefore.containsKey(first(filesAfter.keySet())));

		List<CrdtData<String, Set<Integer>>> downloadedAfter = await(client.download().then(StreamSupplier::toList));
		assertEquals(expected, downloadedAfter);
	}

	@Test
	public void testTombstoneConsolidation() {
		List<CrdtData<String, Set<Integer>>> expected = List.of(
			new CrdtData<>("a", 100, Set.of(1, 2, 3)),
			new CrdtData<>("b", 300, Set.of(5, 6, 7)),
			new CrdtData<>("c", 400, Set.of(78, 2, 3))
		);

		await(StreamSuppliers.ofValues(
			new CrdtData<>("a", 100, Set.of(1, 2, 3)),
			new CrdtData<>("b", 200, Set.of(2, 3, 7))
		).streamTo(client.upload()));
		await(StreamSuppliers.ofValues(
			new CrdtData<>("b", 300, Set.of(5, 6, 7)),
			new CrdtData<>("c", 400, Set.of(78, 2, 3))
		).streamTo(client.upload()));
		await(StreamSuppliers.ofValues(
			new CrdtData<>("c", 100, Set.of(123, 124, 125, 3)),
			new CrdtData<>("d", 500, Set.of(12))
		).streamTo(client.upload()));
		await(StreamSuppliers.ofValues(
			new CrdtData<>("d", 600, Set.of(56, 76)),
			new CrdtData<>("e", 300, Set.of(124))
		).streamTo(client.upload()));

		await(StreamSuppliers.ofStream(Stream.of(new CrdtTombstone<>("a", 50))).streamTo(client.remove()));
		await(StreamSuppliers.ofStream(Stream.of(new CrdtTombstone<>("b", 250))).streamTo(client.remove()));
		await(StreamSuppliers.ofStream(Stream.of(new CrdtTombstone<>("c", 300))).streamTo(client.remove()));
		await(StreamSuppliers.ofStream(Stream.of(new CrdtTombstone<>("d", 600))).streamTo(client.remove()));
		await(StreamSuppliers.ofStream(Stream.of(new CrdtTombstone<>("e", 400))).streamTo(client.remove()));

		Map<String, FileMetadata> filesBefore = await(fileSystem.list("**"));
		System.out.println(filesBefore);
		assertEquals(9, filesBefore.size());

		List<CrdtData<String, Set<Integer>>> downloadedBefore = await(client.download().then(StreamSupplier::toList));
		assertEquals(expected, downloadedBefore);

		await(client.consolidate());

		Map<String, FileMetadata> filesAfter = await(fileSystem.list("**"));
		System.out.println(filesAfter);
		assertEquals(1, filesAfter.size());
		String consolidated = first(filesAfter.keySet());
		assertFalse(filesBefore.containsKey(consolidated));

		List<CrdtData<String, Set<Integer>>> downloadedAfter = await(client.download().then(StreamSupplier::toList));
		assertEquals(expected, downloadedAfter);
	}

	@Test
	public void pickFilesForConsolidation() {
		testPickFilesForConsolidation(
			Set.of("a", "c", "e"),
			Map.of(
				"a", 12,
				"b", 120,
				"c", 53,
				"d", 348,
				"e", 97)
		);
		testPickFilesForConsolidation(
			Set.of("a", "c"),
			Map.of(
				"a", 120,
				"b", 12,
				"c", 530
			));
		testPickFilesForConsolidation(
			Set.of("b", "d"),
			Map.of(
				"a", 120,
				"b", 12,
				"c", 530,
				"d", 43
			));
		testPickFilesForConsolidation(
			Set.of(),
			Map.of(
				"a", 120,
				"b", 12,
				"c", 5,
				"d", 4345
			));
	}

	private static void testPickFilesForConsolidation(Set<String> expected, Map<String, Integer> fileToSizeMap) {
		Map<String, FileMetadata> files = fileToSizeMap.entrySet().stream()
			.collect(entriesToLinkedHashMap(size -> FileMetadata.of(size, 0)));
		Set<String> filesForConsolidation = FileSystemCrdtStorage.pickFilesForConsolidation(files);

		assertEquals(expected, filesForConsolidation);
	}

	private static Set<Integer> union(Set<Integer> first, Set<Integer> second) {
		Set<Integer> res = new HashSet<>(Math.max((int) ((first.size() + second.size()) / .75f) + 1, 16));
		res.addAll(first);
		res.addAll(second);
		return res;
	}
}
