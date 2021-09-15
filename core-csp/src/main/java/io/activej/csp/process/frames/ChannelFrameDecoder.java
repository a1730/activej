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

package io.activej.csp.process.frames;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufs;
import io.activej.common.exception.MalformedDataException;
import io.activej.common.exception.TruncatedDataException;
import io.activej.csp.ChannelConsumer;
import io.activej.csp.ChannelOutput;
import io.activej.csp.binary.BinaryChannelInput;
import io.activej.csp.binary.BinaryChannelSupplier;
import io.activej.csp.dsl.WithBinaryChannelInput;
import io.activej.csp.dsl.WithChannelTransformer;
import io.activej.csp.process.AbstractCommunicatingProcess;
import io.activej.promise.Promise;
import org.jetbrains.annotations.NotNull;

import static io.activej.csp.process.frames.BlockDecoder.END_OF_STREAM;

public final class ChannelFrameDecoder extends AbstractCommunicatingProcess
		implements WithChannelTransformer<ChannelFrameDecoder, ByteBuf, ByteBuf>, WithBinaryChannelInput<ChannelFrameDecoder> {

	private final @NotNull BlockDecoder decoder;
	private boolean decoderResets;

	private ByteBufs bufs;
	private BinaryChannelSupplier input;
	private ChannelConsumer<ByteBuf> output;

	private ChannelFrameDecoder(@NotNull BlockDecoder decoder) {
		this.decoder = decoder;
	}

	public static ChannelFrameDecoder create(@NotNull FrameFormat format) {
		return create(format.createDecoder());
	}

	public static ChannelFrameDecoder create(@NotNull BlockDecoder decoder) {
		return new ChannelFrameDecoder(decoder);
	}

	public ChannelFrameDecoder withDecoderResets() {
		return withDecoderResets(true);
	}

	public ChannelFrameDecoder withDecoderResets(boolean decoderResets) {
		this.decoderResets = decoderResets;
		return this;
	}

	@Override
	public BinaryChannelInput getInput() {
		return input -> {
			this.input = input;
			this.bufs = input.getBufs();
			if (this.input != null && this.output != null) startProcess();
			return getProcessCompletion();
		};
	}

	@SuppressWarnings("ConstantConditions") //check output for clarity
	@Override
	public ChannelOutput<ByteBuf> getOutput() {
		return output -> {
			this.output = sanitize(output);
			if (this.input != null && this.output != null) startProcess();
		};
	}
	// endregion

	@Override
	protected void doProcess() {
		decode()
				.run((result, e) -> {
					if (e instanceof TruncatedDataException) {
						if (bufs.isEmpty()) {
							if (decoder.ignoreMissingEndOfStreamBlock()) {
								output.acceptEndOfStream()
										.whenResult(this::close);
							} else {
								closeEx(new MissingEndOfStreamBlockException(e));
							}
						} else {
							closeEx(new TruncatedBlockException(e));
						}
					} else {
						doSanitize(result, e)
								.whenResult(buf -> {
									if (buf != END_OF_STREAM) {
										output.accept(buf)
												.whenResult(this::doProcess);
									} else {
										input.endOfStream()
												.then(this::doSanitize)
												.then(() -> output.acceptEndOfStream())
												.whenResult(this::close);
									}
								});
					}
				});
	}

	private @NotNull Promise<ByteBuf> decode() {
		while (true) {
			if (!bufs.isEmpty()) {
				try {
					ByteBuf result = decoder.decode(bufs);
					if (result != null) {
						if (decoderResets) decoder.reset();
						return Promise.of(result);
					}
				} catch (MalformedDataException e) {
					closeEx(e);
					return Promise.ofException(e);
				}
			}
			Promise<Void> moreDataPromise = input.needMoreData();
			if (moreDataPromise.isResult()) continue;
			return moreDataPromise
					.then(this::decode);
		}
	}

	@Override
	protected void doClose(Exception e) {
		input.closeEx(e);
		output.closeEx(e);
	}
}
