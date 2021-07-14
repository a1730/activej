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

package io.activej.inject.module;

import io.activej.inject.Key;
import io.activej.inject.Scope;
import io.activej.inject.binding.BindingGenerator;
import io.activej.inject.binding.BindingSet;
import io.activej.inject.binding.BindingTransformer;
import io.activej.inject.binding.Multibinder;
import io.activej.inject.util.Trie;

import java.lang.reflect.Type;
import java.util.Map;
import java.util.Set;

final class SimpleModule implements Module {
	private final Trie<Scope, Map<Key<?>, BindingSet<?>>> bindings;
	private final Map<Type, Set<BindingTransformer<?>>> transformers;
	private final Map<Type, Set<BindingGenerator<?>>> generators;
	private final Map<Key<?>, Multibinder<?>> multibinders;

	public SimpleModule(Trie<Scope, Map<Key<?>, BindingSet<?>>> bindings,
			Map<Type, Set<BindingTransformer<?>>> transformers,
			Map<Type, Set<BindingGenerator<?>>> generators,
			Map<Key<?>, Multibinder<?>> multibinders) {
		this.bindings = bindings;
		this.transformers = transformers;
		this.generators = generators;
		this.multibinders = multibinders;
	}

	@Override
	public Trie<Scope, Map<Key<?>, BindingSet<?>>> getBindings() {
		return bindings;
	}

	@Override
	public Map<Type, Set<BindingTransformer<?>>> getBindingTransformers() {
		return transformers;
	}

	@Override
	public Map<Type, Set<BindingGenerator<?>>> getBindingGenerators() {
		return generators;
	}

	@Override
	public Map<Key<?>, Multibinder<?>> getMultibinders() {
		return multibinders;
	}
}
