package io.activej.datastream.supplier;

import io.activej.common.ApplicationSettings;
import io.activej.common.Checks;

import java.util.concurrent.ExecutionException;

import static io.activej.common.Checks.checkState;
import static io.activej.reactor.Reactive.checkInReactorThread;

public final class BlockingStreamSupplier<T> extends AbstractStreamSupplier<T> {
	private static final boolean CHECKS = Checks.isEnabled(BlockingStreamSupplier.class);

	public static final int DEFAULT_BUFFER_SIZE = ApplicationSettings.getInt(BlockingStreamSupplier.class, "bufferSize", 8192);

	private final Queue queue;

	private volatile boolean endOfStream;

	private BlockingStreamSupplier(int bufferSize) {
		this.queue = new Queue(bufferSize);
	}

	public static <T> BlockingStreamSupplier<T> create() {
		return new BlockingStreamSupplier<>(DEFAULT_BUFFER_SIZE);
	}

	public static <T> BlockingStreamSupplier<T> create(int bufferSize) {
		return new BlockingStreamSupplier<>(bufferSize);
	}

	public int getBufferCapacity() {
		return queue.capacity();
	}

	public int getBufferSize() {
		return queue.size();
	}

	/**
	 * Puts an item to this {@link StreamSupplier}.
	 * Blocks until queue is not full.
	 *
	 * @param item item to be put to this supplier.
	 * @return {@code true} if all data is acknowledged and no more data should be sent to the supplier
	 * @throws InterruptedException if thread is interrupted while blocked
	 * @throws ExecutionException   if some error occurs asynchronously while putting an item
	 */
	public boolean put(T item) throws InterruptedException, ExecutionException {
		checkState(!endOfStream);

		queue.put(item);

		if (isException()) {
			throw new ExecutionException(getAcknowledgement().getException());
		}

		return isResult();
	}

	public void putEndOfStream() {
		endOfStream = true;
		reactor.submit(queue::onMoreData);
	}

	@Override
	protected void onError(Exception e) {
		queue.close();
	}

	@Override
	protected void onAcknowledge() {
		queue.close();
	}

	public class Queue extends BlockingPutQueue<T> {
		public Queue(int capacity) {
			super(capacity);
		}

		@Override
		protected void onMoreData() {
			if (CHECKS) checkInReactorThread(this);

			while (isReady() && !isEmpty()) {
				send(take());
			}

			if (endOfStream && isEmpty()) {
				sendEndOfStream();
			}
		}
	}

}
