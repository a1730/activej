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

package io.activej.http.loader;

import io.activej.bytebuf.ByteBuf;
import io.activej.common.Checks;
import io.activej.promise.Promise;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.Executor;

import static io.activej.reactor.Reactive.checkInReactorThread;

public class ClassPathStaticLoader extends AbstractReactive
	implements IStaticLoader {
	private static final boolean CHECKS = Checks.isEnabled(ClassPathStaticLoader.class);

	private static final String ROOT = "/";
	private static final int ROOT_OFFSET = 1;
	private final Executor executor;
	private final ClassLoader classLoader;
	private final String root;

	private ClassPathStaticLoader(Reactor reactor, Executor executor, ClassLoader classLoader, String root) {
		super(reactor);
		this.root = root;
		this.executor = executor;
		this.classLoader = classLoader;
	}

	static ClassPathStaticLoader create(Reactor reactor, Executor executor, String root) {
		return create(reactor, executor, Thread.currentThread().getContextClassLoader(), root);
	}

	static ClassPathStaticLoader create(Reactor reactor, Executor executor, ClassLoader classLoader, String root) {
		if (root.startsWith(ROOT)) {
			root = root.substring(ROOT_OFFSET);
		}
		if (!root.endsWith(ROOT) && !root.isEmpty()) {
			root = root + ROOT;
		}

		return new ClassPathStaticLoader(reactor, executor, classLoader, root);
	}

	@Override
	public Promise<ByteBuf> load(String name) {
		if (CHECKS) checkInReactorThread(this);
		String path = root;
		int begin = 0;
		if (name.startsWith(ROOT)) {
			begin++;
		}
		path += name.substring(begin);

		String finalPath = path;

		return Promise.ofBlocking(executor, () -> {
			URL resource = classLoader.getResource(finalPath);
			if (resource == null) {
				throw new ResourceNotFoundException("Could not find '" + name + "' in class path");
			}

			URLConnection connection = resource.openConnection();

			if (connection instanceof JarURLConnection) {
				if (((JarURLConnection) connection).getJarEntry().isDirectory()) {
					throw new ResourceIsADirectoryException("Resource '" + name + "' is a directory");
				}
			} else if ("file".equals(resource.getProtocol())) {
				Path filePath = Paths.get(resource.toURI());
				if (!Files.isRegularFile(filePath)) {
					if (Files.isDirectory(filePath)) {
						throw new ResourceIsADirectoryException("Resource '" + name + "' is a directory");
					} else {
						throw new ResourceNotFoundException("Could not find '" + name + "' in class path");
					}
				}
			}
			return ByteBuf.wrapForReading(loadResource(connection));
		});
	}

	private byte[] loadResource(URLConnection connection) throws IOException {
		try (InputStream stream = connection.getInputStream()) {
			return stream.readAllBytes();
		}
	}
}
