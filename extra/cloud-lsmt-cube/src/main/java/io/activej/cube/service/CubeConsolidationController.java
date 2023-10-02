/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.cube.service;

import io.activej.async.function.AsyncRunnable;
import io.activej.common.builder.AbstractBuilder;
import io.activej.cube.AggregationExecutor;
import io.activej.cube.AggregationState;
import io.activej.cube.CubeConsolidator;
import io.activej.cube.CubeConsolidator.ConsolidationStrategy;
import io.activej.cube.aggregation.*;
import io.activej.cube.aggregation.ot.AggregationDiff;
import io.activej.cube.exception.CubeException;
import io.activej.cube.ot.CubeDiff;
import io.activej.cube.ot.CubeDiffScheme;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.api.attribute.JmxOperation;
import io.activej.jmx.stats.ValueStats;
import io.activej.ot.OTStateManager;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.promise.jmx.PromiseStats;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import io.activej.reactor.jmx.ReactiveJmxBeanWithStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.activej.async.function.AsyncRunnables.reuse;
import static io.activej.async.util.LogUtils.thisMethod;
import static io.activej.async.util.LogUtils.toLogger;
import static io.activej.common.Checks.checkState;
import static io.activej.common.Utils.entriesToLinkedHashMap;
import static io.activej.cube.aggregation.util.Utils.collectChunkIds;
import static io.activej.reactor.Reactive.checkInReactorThread;
import static java.util.stream.Collectors.toSet;

public final class CubeConsolidationController<K, D, C> extends AbstractReactive
	implements ReactiveJmxBeanWithStats {

	private static final Logger logger = LoggerFactory.getLogger(CubeConsolidationController.class);

	public static final Supplier<LockerStrategy> DEFAULT_CONSOLIDATION_STRATEGY = new Supplier<>() {
		private boolean hotSegment = false;

		@Override
		public LockerStrategy get() {
			hotSegment = !hotSegment;
			return lockedChunkIds ->
				hotSegment ?
					ConsolidationStrategy.hotSegment(lockedChunkIds) :
					ConsolidationStrategy.minKey(lockedChunkIds);
		}
	};

	public static final Duration DEFAULT_SMOOTHING_WINDOW = Duration.ofMinutes(5);

	private final CubeDiffScheme<D> cubeDiffScheme;
	private final CubeConsolidator cubeConsolidator;
	private final OTStateManager<K, D> stateManager;
	private final IAggregationChunkStorage<C> aggregationChunkStorage;

	private final PromiseStats promiseConsolidate = PromiseStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final PromiseStats promiseConsolidateImpl = PromiseStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final PromiseStats promiseCleanupIrrelevantChunks = PromiseStats.create(DEFAULT_SMOOTHING_WINDOW);

	private final ValueStats removedChunks = ValueStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final ValueStats removedChunksRecords = ValueStats.builder(DEFAULT_SMOOTHING_WINDOW)
		.withRate()
		.build();
	private final ValueStats addedChunks = ValueStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final ValueStats addedChunksRecords = ValueStats.builder(DEFAULT_SMOOTHING_WINDOW)
		.withRate()
		.build();

	private final Map<String, IChunkLocker<Object>> lockers = new HashMap<>();

	private Supplier<LockerStrategy> strategy = DEFAULT_CONSOLIDATION_STRATEGY;
	private Function<String, IChunkLocker<C>> chunkLockerFactory = $ -> NoOpChunkLocker.create(reactor);

	private boolean consolidating;
	private boolean cleaning;

	private CubeConsolidationController(
		Reactor reactor, CubeDiffScheme<D> cubeDiffScheme, CubeConsolidator cubeConsolidator, OTStateManager<K, D> stateManager,
		IAggregationChunkStorage<C> aggregationChunkStorage
	) {
		super(reactor);
		this.cubeDiffScheme = cubeDiffScheme;
		this.cubeConsolidator = cubeConsolidator;
		this.stateManager = stateManager;
		this.aggregationChunkStorage = aggregationChunkStorage;
	}

	public static <K, D, C> CubeConsolidationController<K, D, C> create(
		Reactor reactor, CubeDiffScheme<D> cubeDiffScheme, CubeConsolidator cubeConsolidator, OTStateManager<K, D> stateManager,
		IAggregationChunkStorage<C> aggregationChunkStorage
	) {
		return builder(reactor, cubeDiffScheme, cubeConsolidator, stateManager, aggregationChunkStorage).build();
	}

	public static <K, D, C> CubeConsolidationController<K, D, C>.Builder builder(
		Reactor reactor, CubeDiffScheme<D> cubeDiffScheme, CubeConsolidator cubeConsolidator, OTStateManager<K, D> stateManager,
		IAggregationChunkStorage<C> aggregationChunkStorage
	) {
		return new CubeConsolidationController<>(reactor, cubeDiffScheme, cubeConsolidator, stateManager, aggregationChunkStorage).new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, CubeConsolidationController<K, D, C>> {
		private Builder() {}

		public Builder withLockerStrategy(Supplier<LockerStrategy> strategy) {
			checkNotBuilt(this);
			CubeConsolidationController.this.strategy = strategy;
			return this;
		}

		public Builder withChunkLockerFactory(Function<String, IChunkLocker<C>> factory) {
			checkNotBuilt(this);
			CubeConsolidationController.this.chunkLockerFactory = factory;
			return this;
		}

		@Override
		protected CubeConsolidationController<K, D, C> doBuild() {
			return CubeConsolidationController.this;
		}
	}

	private final AsyncRunnable consolidate = reuse(this::doConsolidate);
	private final AsyncRunnable cleanupIrrelevantChunks = reuse(this::doCleanupIrrelevantChunks);

	@SuppressWarnings("UnusedReturnValue")
	public Promise<Void> consolidate() {
		checkInReactorThread(this);
		return consolidate.run();
	}

	@SuppressWarnings("UnusedReturnValue")
	public Promise<Void> cleanupIrrelevantChunks() {
		checkInReactorThread(this);
		return cleanupIrrelevantChunks.run();
	}

	Promise<Void> doConsolidate() {
		checkInReactorThread(this);
		checkState(!cleaning, "Cannot consolidate and clean up irrelevant chunks at the same time");
		consolidating = true;
		LockerStrategy chunksFn = strategy.get();
		Map<String, List<AggregationChunk>> chunksForConsolidation = new HashMap<>();
		return Promise.complete()
			.then(stateManager::sync)
			.mapException(e -> new CubeException("Failed to synchronize state prior to consolidation", e))
			.then(() -> Promises.all(cubeConsolidator.getStructure().getAggregationIds().stream()
				.map(aggregationId -> findAndLockChunksForConsolidation(aggregationId, chunksFn)
					.whenResult(chunks -> {
						if (!chunks.isEmpty()) chunksForConsolidation.put(aggregationId, chunks);
					}))))
			.then(() -> cubeConsolidator.consolidate((id, $1, $2, $3) -> chunksForConsolidation.getOrDefault(id, List.of()))
				.whenComplete(promiseConsolidateImpl.recordStats()))
			.whenResult(this::cubeDiffJmx)
			.whenComplete(this::logCubeDiff)
			.then(cubeDiff -> {
				if (cubeDiff.isEmpty()) return Promise.complete();
				return aggregationChunkStorage.finish(addedChunks(cubeDiff))
					.mapException(e -> new CubeException("Failed to finalize chunks in storage", e))
					.whenResult(() -> stateManager.add(cubeDiffScheme.wrap(cubeDiff)))
					.then(() -> stateManager.sync()
						.mapException(e -> new CubeException("Failed to synchronize state after consolidation, resetting", e)))
					.whenException(e -> stateManager.reset())
					.whenComplete(toLogger(logger, thisMethod(), cubeDiff));
			})
			.then((result, e) -> releaseChunks(chunksForConsolidation)
				.then(() -> Promise.of(result, e)))
			.whenComplete(promiseConsolidate.recordStats())
			.whenComplete(toLogger(logger, thisMethod(), stateManager))
			.whenComplete(() -> consolidating = false);
	}

	private Promise<List<AggregationChunk>> findAndLockChunksForConsolidation(
		String aggregationId, LockerStrategy strategy
	) {
		IChunkLocker<Object> locker = ensureLocker(aggregationId);
		AggregationExecutor aggregationExecutor = cubeConsolidator.getExecutor().getAggregationExecutors().get(aggregationId);
		AggregationState aggregationState = cubeConsolidator.getState().getAggregationStates().get(aggregationId);

		return Promises.retry(($, e) -> !(e instanceof ChunksAlreadyLockedException),
			() -> locker.getLockedChunks()
				.map(strategy::getConsolidationStrategy)
				.map(consolidationStrategy -> consolidationStrategy.getChunksForConsolidation(
					aggregationId,
					aggregationState,
					aggregationExecutor.getMaxChunksToConsolidate(),
					aggregationExecutor.getChunkSize()
				))
				.then(chunks -> {
					if (chunks.isEmpty()) {
						logger.info("Nothing to consolidate in aggregation '{}'", aggregationId);
						return Promise.of(chunks);
					}
					return locker.lockChunks(collectChunkIds(chunks))
						.map($ -> chunks);
				}));
	}

	private Promise<Void> releaseChunks(Map<String, List<AggregationChunk>> chunksForConsolidation) {
		return Promises.all(chunksForConsolidation.entrySet().stream()
			.map(entry -> {
				String aggregationId = entry.getKey();
				Set<Object> chunkIds = collectChunkIds(entry.getValue());
				return ensureLocker(aggregationId).releaseChunks(chunkIds)
					.map(($, e) -> {
						if (e != null) {
							logger.warn("Failed to release chunks: {} in aggregation {}",
								chunkIds, aggregationId, e);
						}
						return null;
					});
			}));
	}

	private Promise<Void> doCleanupIrrelevantChunks() {
		checkState(!consolidating, "Cannot consolidate and clean up irrelevant chunks at the same time");
		cleaning = true;
		return stateManager.sync()
			.mapException(e -> new CubeException("Failed to synchronize state prior to cleaning up irrelevant chunks", e))
			.then(() -> {
				Map<String, Set<AggregationChunk>> irrelevantChunks = cubeConsolidator.getState().getIrrelevantChunks();
				if (irrelevantChunks.isEmpty()) {
					logger.info("Found no irrelevant chunks");
					return Promise.complete();
				}
				logger.info("Removing irrelevant chunks: {}", irrelevantChunks.keySet());
				Map<String, AggregationDiff> diffMap = irrelevantChunks.entrySet().stream()
					.collect(entriesToLinkedHashMap(chunksToRemove -> AggregationDiff.of(Set.of(), chunksToRemove)));
				CubeDiff cubeDiff = CubeDiff.of(diffMap);
				cubeDiffJmx(cubeDiff);
				stateManager.add(cubeDiffScheme.wrap(cubeDiff));
				return stateManager.sync()
					.mapException(e -> new CubeException("Failed to synchronize state after cleaning up irrelevant chunks, resetting", e))
					.whenException(e -> stateManager.reset());
			})
			.whenComplete(promiseCleanupIrrelevantChunks.recordStats())
			.whenComplete(toLogger(logger, thisMethod(), stateManager))
			.whenComplete(() -> cleaning = false);
	}

	private void cubeDiffJmx(CubeDiff cubeDiff) {
		long curAddedChunks = 0;
		long curAddedChunksRecords = 0;
		long curRemovedChunks = 0;
		long curRemovedChunksRecords = 0;

		for (String key : cubeDiff.keySet()) {
			AggregationDiff aggregationDiff = cubeDiff.get(key);
			curAddedChunks += aggregationDiff.getAddedChunks().size();
			for (AggregationChunk aggregationChunk : aggregationDiff.getAddedChunks()) {
				curAddedChunksRecords += aggregationChunk.getCount();
			}

			curRemovedChunks += aggregationDiff.getRemovedChunks().size();
			for (AggregationChunk aggregationChunk : aggregationDiff.getRemovedChunks()) {
				curRemovedChunksRecords += aggregationChunk.getCount();
			}
		}

		addedChunks.recordValue(curAddedChunks);
		addedChunksRecords.recordValue(curAddedChunksRecords);
		removedChunks.recordValue(curRemovedChunks);
		removedChunksRecords.recordValue(curRemovedChunksRecords);
	}

	@SuppressWarnings("unchecked")
	private static <C> Set<C> addedChunks(CubeDiff cubeDiff) {
		return cubeDiff.addedChunks().map(id -> (C) id).collect(toSet());
	}

	private void logCubeDiff(CubeDiff cubeDiff, Exception e) {
		if (e != null) logger.warn("Consolidation failed", e);
		else if (cubeDiff.isEmpty()) logger.info("Previous consolidation did not merge any chunks");
		else logger.info("Consolidation finished. Launching consolidation task again.");
	}

	private IChunkLocker<Object> ensureLocker(String aggregationId) {
		//noinspection unchecked
		return lockers.computeIfAbsent(aggregationId, $ -> (IChunkLocker<Object>) chunkLockerFactory.apply(aggregationId));
	}

	public interface LockerStrategy {
		ConsolidationStrategy getConsolidationStrategy(Set<Object> lockedChunkIds);
	}

	@JmxAttribute
	public ValueStats getRemovedChunks() {
		return removedChunks;
	}

	@JmxAttribute
	public ValueStats getAddedChunks() {
		return addedChunks;
	}

	@JmxAttribute
	public ValueStats getRemovedChunksRecords() {
		return removedChunksRecords;
	}

	@JmxAttribute
	public ValueStats getAddedChunksRecords() {
		return addedChunksRecords;
	}

	@JmxAttribute
	public PromiseStats getPromiseConsolidate() {
		return promiseConsolidate;
	}

	@JmxAttribute
	public PromiseStats getPromiseConsolidateImpl() {
		return promiseConsolidateImpl;
	}

	@JmxAttribute
	public PromiseStats getPromiseCleanupIrrelevantChunks() {
		return promiseCleanupIrrelevantChunks;
	}

	@JmxOperation
	public void consolidateNow() {
		consolidate();
	}

	@JmxOperation
	public void cleanupIrrelevantChunksNow() {
		cleanupIrrelevantChunks();
	}

}
