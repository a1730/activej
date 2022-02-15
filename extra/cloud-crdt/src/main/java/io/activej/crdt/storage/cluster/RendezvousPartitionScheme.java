package io.activej.crdt.storage.cluster;

import io.activej.common.initializer.WithInitializer;
import io.activej.crdt.storage.CrdtStorage;
import io.activej.crdt.storage.cluster.DiscoveryService.PartitionScheme;
import io.activej.rpc.client.sender.RpcStrategy;
import io.activej.rpc.client.sender.RpcStrategyRendezvousHashing;
import io.activej.rpc.client.sender.RpcStrategySharding;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import static io.activej.crdt.storage.cluster.RendezvousHashSharder.NUMBER_OF_BUCKETS;
import static java.util.stream.Collectors.toSet;

public final class RendezvousPartitionScheme<P> implements PartitionScheme<P>, WithInitializer<RendezvousPartitionScheme<P>> {
	private final List<RendezvousPartitionGroup<P>> partitionGroups = new ArrayList<>();
	private ToIntFunction<?> keyHashFn = Object::hashCode;
	@SuppressWarnings("unchecked")
	private Function<P, Object> partitionIdGetter = (Function<P, Object>) Function.identity();
	private Function<P, @NotNull RpcStrategy> rpcProvider;
	private Function<P, @NotNull CrdtStorage<?, ?>> crdtProvider;

	@SafeVarargs
	public static <P> RendezvousPartitionScheme<P> create(RendezvousPartitionGroup<P>... partitionGroups) {
		return create(Arrays.asList(partitionGroups));
	}

	public static <P> RendezvousPartitionScheme<P> create(List<RendezvousPartitionGroup<P>> partitionGroups) {
		RendezvousPartitionScheme<P> scheme = new RendezvousPartitionScheme<>();
		scheme.partitionGroups.addAll(partitionGroups);
		return scheme;
	}

	public RendezvousPartitionScheme<P> withPartitionIdGetter(Function<P, Object> partitionIdGetter) {
		this.partitionIdGetter = partitionIdGetter;
		return this;
	}

	public RendezvousPartitionScheme<P> withCrdtProvider(Function<P, CrdtStorage<?, ?>> crdtProvider) {
		this.crdtProvider = crdtProvider;
		return this;
	}

	public RendezvousPartitionScheme<P> withRpcProvider(Function<P, RpcStrategy> rpcProvider) {
		this.rpcProvider = rpcProvider;
		return this;
	}

	public RendezvousPartitionScheme<P> withPartitionGroup(RendezvousPartitionGroup<P> partitionGroup) {
		this.partitionGroups.add(partitionGroup);
		return this;
	}

	public <K extends Comparable<K>> RendezvousPartitionScheme<P> withKeyHashFn(ToIntFunction<K> keyHashFn) {
		this.keyHashFn = keyHashFn;
		return this;
	}

	@Override
	public Set<P> getPartitions() {
		return partitionGroups.stream().flatMap(g -> g.getPartitionIds().stream()).collect(toSet());
	}

	@Override
	public CrdtStorage<?, ?> provideCrdtConnection(P partition) {
		return crdtProvider.apply(partition);
	}

	@Override
	@NotNull
	public RpcStrategy provideRpcConnection(P partition) {
		return rpcProvider.apply(partition);
	}

	@Override
	public <K extends Comparable<K>> @Nullable Sharder<K> createSharder(List<P> alive) {
		List<RendezvousHashSharder<K>> sharders = new ArrayList<>();
		for (RendezvousPartitionGroup<P> partitionGroup : partitionGroups) {
			//noinspection unchecked
			RendezvousHashSharder<K> sharder = RendezvousHashSharder.create(
					((ToIntFunction<K>) keyHashFn),
					p -> partitionIdGetter.apply(p).hashCode(),
					partitionGroup.getPartitionIds(),
					alive,
					partitionGroup.getReplicaCount(), partitionGroup.isRepartition());
			sharders.add(sharder);
		}
		return RendezvousHashSharder.unionOf(sharders);
	}

	@Override
	public <K extends Comparable<K>> RpcStrategy createRpcStrategy(Function<Object, K> keyGetter) {

		List<RpcStrategy> rendezvousHashings = new ArrayList<>();
		for (RendezvousPartitionGroup<P> partitionGroup : partitionGroups) {
			if (!partitionGroup.isActive()) continue;
			//noinspection unchecked
			rendezvousHashings.add(
					RpcStrategyRendezvousHashing.create(req ->
									((ToIntFunction<K>) keyHashFn).applyAsInt(keyGetter.apply(req)))
							.withBuckets(NUMBER_OF_BUCKETS)
							.withHashBucketFn((p, bucket) -> RendezvousHashSharder.hashBucket(partitionIdGetter.apply((P) p).hashCode(), bucket))
							.withMinActiveShards(1)
							.withInitializer(rendezvousHashing -> {
								for (P partitionId : partitionGroup.getPartitionIds()) {
									rendezvousHashing.withShard(partitionId, provideRpcConnection(partitionId));
								}
								if (!partitionGroup.isRepartition()) {
									rendezvousHashing.withReshardings(partitionGroup.getReplicaCount());
								}
							}));
		}

		return RpcStrategySharding.create(
				new ToIntFunction<Object>() {
					final int count = rendezvousHashings.size();

					@Override
					public int applyAsInt(Object item) {
						return keyGetter.apply(item).hashCode() % count;
					}
				},
				rendezvousHashings);
	}

}
