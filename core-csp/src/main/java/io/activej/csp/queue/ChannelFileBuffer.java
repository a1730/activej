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

package io.activej.csp.queue;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.Checks;
import io.activej.common.MemSize;
import io.activej.common.tuple.Tuple2;
import io.activej.csp.file.ChannelFileReader;
import io.activej.csp.file.ChannelFileWriter;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.ImplicitlyReactive;
import io.activej.reactor.Reactor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;

import static io.activej.reactor.Reactive.checkInReactorThread;
import static java.nio.file.StandardOpenOption.*;

public final class ChannelFileBuffer extends ImplicitlyReactive implements ChannelQueue<ByteBuf> {
	private static final Logger logger = LoggerFactory.getLogger(ChannelFileBuffer.class);
	private static final boolean CHECKS = Checks.isEnabled(ChannelFileBuffer.class);

	private final ChannelFileReader reader;
	private final ChannelFileWriter writer;
	private final Executor executor;
	private final Path path;

	private @Nullable SettablePromise<ByteBuf> take;

	private boolean finished = false;

	private @Nullable Exception exception;

	private ChannelFileBuffer(ChannelFileReader reader, ChannelFileWriter writer, Executor executor, Path path) {
		this.reader = reader;
		this.writer = writer;
		this.executor = executor;
		this.path = path;
	}

	public static Promise<ChannelFileBuffer> create(Executor executor, Path filePath) {
		return create(executor, filePath, null);
	}

	public static Promise<ChannelFileBuffer> create(Executor executor, Path path, @Nullable MemSize limit) {
		return Promise.ofBlocking(executor,
				() -> {
					Files.createDirectories(path.getParent());
					FileChannel writerChannel = FileChannel.open(path, CREATE, WRITE);
					FileChannel readerChannel = FileChannel.open(path, CREATE, READ);
					return new Tuple2<>(writerChannel, readerChannel);
				})
			.map(tuple2 -> {
				Reactor reactor = Reactor.getCurrentReactor();
				ChannelFileWriter writer = ChannelFileWriter.create(reactor, executor, tuple2.value1());
				ChannelFileReader.Builder readerBuilder = ChannelFileReader.builder(reactor, executor, tuple2.value2());
				if (limit != null) {
					readerBuilder.withLimit(limit.toLong());
				}
				ChannelFileReader reader = readerBuilder.build();
				return new ChannelFileBuffer(reader, writer, executor, path);
			});
	}

	@Override
	public Promise<Void> put(@Nullable ByteBuf item) {
		if (CHECKS) checkInReactorThread(this);
		if (exception != null) {
			return Promise.ofException(exception);
		}
		if (item == null) {
			finished = true;
		}
		if (take == null) {
			return writer.accept(item);
		}
		SettablePromise<ByteBuf> promise = take;
		take = null;
		promise.set(item);
		return item == null ?
			writer.accept(null) :
			Promise.complete();
	}

	@Override
	public Promise<ByteBuf> take() {
		if (CHECKS) checkInReactorThread(this);
		if (exception != null) {
			return Promise.ofException(exception);
		}
		if (!isExhausted()) {
			return reader.get();
		}
		if (finished) {
			return Promise.of(null);
		}
		SettablePromise<ByteBuf> promise = new SettablePromise<>();
		take = promise;
		return promise;
	}

	@Override
	public boolean isSaturated() {
		return false;
	}

	@Override
	public boolean isExhausted() {
		return reader.getPosition() >= writer.getPosition();
	}

	@Override
	public void closeEx(Exception e) {
		checkInReactorThread(this);
		if (exception != null) {
			return;
		}
		exception = e;
		writer.closeEx(e);
		reader.closeEx(e);

		if (take != null) {
			take.setException(e);
			take = null;
		}

		// each queue should operate on files with unique names
		// to avoid races due to this
		executor.execute(() -> {
			try {
				Files.deleteIfExists(path);
			} catch (IOException io) {
				logger.error("Failed to cleanup channel buffer file {}", path, io);
			}
		});
	}

	public @Nullable Exception getException() {
		return exception;
	}
}
