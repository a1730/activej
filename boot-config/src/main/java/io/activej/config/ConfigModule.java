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

package io.activej.config;

import io.activej.common.builder.AbstractBuilder;
import io.activej.inject.Key;
import io.activej.inject.KeyPattern;
import io.activej.inject.module.AbstractModule;
import io.activej.launcher.annotation.OnStart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.Checks.checkState;

/**
 * Supplies config to your application, looks after usage of config, prevents usage of config in any part of lifecycle except for startup.
 */
public final class ConfigModule extends AbstractModule {
	private static final Logger logger = LoggerFactory.getLogger(ConfigModule.class);

	private Path effectiveConfigPath;
	private Consumer<String> effectiveConfigConsumer;

	static final class Config_Protected implements Config {
		private final Config config;
		private final Map<String, Config> children;
		private final AtomicBoolean started;

		Config_Protected(Config config, AtomicBoolean started) {
			this.config = config;
			this.started = started;
			this.children = new LinkedHashMap<>();
			config.getChildren().forEach((key, value) ->
					this.children.put(key, new Config_Protected(value, started)));
		}

		@Override
		public String getValue(String defaultValue) {
			checkState(!started.get(), "Config must be used during application start-up time only");
			return config.getValue(defaultValue);
		}

		@Override
		public String getValue() throws NoSuchElementException {
			checkState(!started.get(), "Config must be used during application start-up time only");
			return config.getValue();
		}

		@Override
		public Map<String, Config> getChildren() {
			return children;
		}

		@Override
		public Config provideNoKeyChild(String key) {
			checkArgument(!children.containsKey(key), "Children already contain key '%s'", key);
			return new Config_Protected(config.provideNoKeyChild(key), started);
		}
	}

	public static ConfigModule create() {
		return builder().build();
	}

	public static Builder builder() {
		return new ConfigModule().new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, ConfigModule> {
		private Builder() {}

		public Builder withEffectiveConfigConsumer(Consumer<String> consumer) {
			checkNotBuilt(this);
			effectiveConfigConsumer = consumer;
			return this;
		}

		public Builder withEffectiveConfigLogger(Writer writer) {
			checkNotBuilt(this);
			return withEffectiveConfigConsumer(effectiveConfig -> {
				try {
					writer.write(effectiveConfig);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}

		public Builder withEffectiveConfigLogger(PrintStream writer) {
			checkNotBuilt(this);
			return withEffectiveConfigConsumer(writer::print);
		}

		public Builder withEffectiveConfigLogger() {
			checkNotBuilt(this);
			return withEffectiveConfigConsumer(effectiveConfig ->
					logger.info("Effective Config:\n\n{}", effectiveConfig));
		}

		@Override
		protected ConfigModule doBuild() {
			return ConfigModule.this;
		}
	}

	public ConfigModule saveEffectiveConfigTo(String file) {
		return saveEffectiveConfigTo(Paths.get(file));
	}

	public ConfigModule saveEffectiveConfigTo(Path file) {
		this.effectiveConfigPath = file;
		return this;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected void configure() {
		transform(KeyPattern.of(Config.class, null), (bindings, scope, key, binding) -> {
			Key<CompletionStage<Void>> completionStageKey = new Key<>(OnStart.class) {};
			return binding
					.addDependencies(completionStageKey)
					.mapInstance(List.of(completionStageKey), (args, config) -> {
						CompletionStage<Void> onStart = (CompletionStage<Void>) args[0];
						AtomicBoolean started = new AtomicBoolean();
						Config_Protected protectedConfig = new Config_Protected(Config_WithFullPath.wrap(config), started);
						Config_Effective effectiveConfig = Config_Effective.wrap(protectedConfig);
						onStart.thenRun(() -> save(effectiveConfig, started));
						return effectiveConfig;
					});
		});
	}

	private void save(Config_Effective effectiveConfig, AtomicBoolean started) {
		started.set(true);
		if (effectiveConfigPath != null) {
			logger.info("Saving effective config to {}", effectiveConfigPath);
			effectiveConfig.saveEffectiveConfigTo(effectiveConfigPath);
		}
		if (effectiveConfigConsumer != null) {
			effectiveConfigConsumer.accept(effectiveConfig.renderEffectiveConfig());
		}
	}
}
