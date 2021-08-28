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

package io.activej.promise;

import io.activej.async.callback.Callback;
import io.activej.common.collection.Try;
import io.activej.common.function.*;
import io.activej.common.recycle.Recyclers;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.*;

import static io.activej.eventloop.Eventloop.getCurrentEventloop;
import static io.activej.eventloop.util.RunnableWithContext.wrapContext;

/**
 * Represents a {@code Promise} which is completed with an exception.
 */
@SuppressWarnings("unchecked")
public final class CompleteExceptionallyPromise<T> implements Promise<T> {
	@NotNull
	private final Throwable exception;

	public CompleteExceptionallyPromise(@NotNull Throwable e) {
		this.exception = e;
	}

	@Override
	public boolean isComplete() {
		return true;
	}

	@Override
	public boolean isResult() {
		return false;
	}

	@Override
	public boolean isException() {
		return true;
	}

	@Override
	public T getResult() {
		return null;
	}

	@Override
	public Throwable getException() {
		return exception;
	}

	@Override
	public Try<T> getTry() {
		return Try.ofException(exception);
	}

	@NotNull
	@Override
	public <U, S extends Callback<? super T> & Promise<U>> Promise<U> next(@NotNull S promise) {
		promise.accept(null, exception);
		return promise;
	}

	@NotNull
	@SuppressWarnings("unchecked")
	@Override
	public <U> Promise<U> map(@NotNull Function<? super T, ? extends U> fn) {
		return (Promise<U>) this;
	}

	@NotNull
	@SuppressWarnings("unchecked")
	@Override
	public <U> Promise<U> mapEx(@NotNull ThrowingFunction<? super T, ? extends U> fn) {
		return (Promise<U>) this;
	}

	@NotNull
	@Override
	public <U> Promise<U> map(@NotNull BiFunction<? super T, Throwable, ? extends U> fn) {
		return Promise.of(fn.apply(null, exception));
	}

	@NotNull
	@Override
	public <U> Promise<U> mapEx(@NotNull ThrowingBiFunction<? super T, Throwable, ? extends U> fn) {
		try {
			return Promise.of(fn.apply(null, exception));
		} catch (Exception e) {
			return Promise.ofException(e);
		}
	}

	@Override
	public @NotNull <U> Promise<U> then(@NotNull Supplier<? extends Promise<? extends U>> fn) {
		return (Promise<U>) this;
	}

	@Override
	public @NotNull <U> Promise<U> thenEx(@NotNull ThrowingFunction<? super T, ? extends Promise<? extends U>> fn) {
		return (Promise<U>) this;
	}

	@NotNull
	@Override
	public <U> Promise<U> then(@NotNull Function<? super T, ? extends Promise<? extends U>> fn) {
		return (Promise<U>) this;
	}

	@Override
	public @NotNull <U> Promise<U> thenEx(@NotNull ThrowingSupplier<? extends Promise<? extends U>> fn) {
		return (Promise<U>) this;
	}

	@NotNull
	@Override
	public <U> Promise<U> then(@NotNull BiFunction<? super T, Throwable, ? extends Promise<? extends U>> fn) {
		return (Promise<U>) fn.apply(null, exception);
	}

	@NotNull
	@Override
	public <U> Promise<U> thenEx(@NotNull ThrowingBiFunction<? super T, Throwable, ? extends Promise<? extends U>> fn) {
		try {
			return (Promise<U>) fn.apply(null, exception);
		} catch (Exception e) {
			return Promise.ofException(e);
		}
	}

	@NotNull
	@Override
	public Promise<T> whenComplete(@NotNull BiConsumer<? super T, Throwable> action) {
		action.accept(null, exception);
		return this;
	}

	@NotNull
	@Override
	public Promise<T> whenComplete(@NotNull Runnable action) {
		action.run();
		return this;
	}

	@Override
	public @NotNull Promise<T> whenCompleteEx(@NotNull ThrowingRunnable action) {
		try {
			action.run();
			return this;
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			return Promise.ofException(ex);
		}
	}

	@NotNull
	@Override
	public Promise<T> whenResult(@NotNull Consumer<? super T> action) {
		return this;
	}

	@Override
	public @NotNull Promise<T> whenResultEx(ThrowingConsumer<? super T> action) {
		return this;
	}

	@Override
	public @NotNull Promise<T> whenResult(@NotNull Runnable action) {
		return this;
	}

	@Override
	public @NotNull Promise<T> whenResultEx(@NotNull ThrowingRunnable action) {
		return this;
	}

	@Override
	public Promise<T> whenException(@NotNull Consumer<Throwable> action) {
		action.accept(exception);
		return this;
	}

	@Override
	public Promise<T> whenException(@NotNull Runnable action) {
		action.run();
		return this;
	}

	@NotNull
	@Override
	public <U, V> Promise<V> combine(@NotNull Promise<? extends U> other, @NotNull BiFunction<? super T, ? super U, ? extends V> fn) {
		other.whenResult(Recyclers::recycle);
		return (Promise<V>) this;
	}

	@NotNull
	@Override
	public Promise<Void> both(@NotNull Promise<?> other) {
		other.whenResult(Recyclers::recycle);
		return (Promise<Void>) this;
	}

	@NotNull
	@Override
	public Promise<T> either(@NotNull Promise<? extends T> other) {
		return (Promise<T>) other;
	}

	@NotNull
	@Override
	public Promise<T> async() {
		SettablePromise<T> result = new SettablePromise<>();
		getCurrentEventloop().post(wrapContext(result, () -> result.setException(exception)));
		return result;
	}

	@NotNull
	@Override
	public Promise<Try<T>> toTry() {
		return Promise.of(Try.ofException(exception));
	}

	@NotNull
	@Override
	public Promise<Void> toVoid() {
		return (Promise<Void>) this;
	}

	@Override
	public void run(@NotNull Callback<? super T> action) {
		action.accept(null, exception);
	}

	@NotNull
	@Override
	public CompletableFuture<T> toCompletableFuture() {
		CompletableFuture<T> future = new CompletableFuture<>();
		future.completeExceptionally(exception);
		return future;
	}
}
