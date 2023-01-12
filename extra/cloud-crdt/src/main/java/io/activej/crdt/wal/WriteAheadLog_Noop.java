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

package io.activej.crdt.wal;

import io.activej.promise.Promise;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;

public class WriteAheadLog_Noop<K extends Comparable<K>, S> extends AbstractReactive
		implements AsyncWriteAheadLog<K, S> {

	private WriteAheadLog_Noop(Reactor reactor) {
		super(reactor);
	}

	public static <K extends Comparable<K>, S> WriteAheadLog_Noop<K, S> create(Reactor reactor) {
		return new WriteAheadLog_Noop<K, S>(reactor);
	}

	@Override
	public Promise<Void> put(K key, S value) {
		checkInReactorThread();
		return Promise.complete();
	}

	@Override
	public Promise<Void> flush() {
		checkInReactorThread();
		return Promise.complete();
	}
}