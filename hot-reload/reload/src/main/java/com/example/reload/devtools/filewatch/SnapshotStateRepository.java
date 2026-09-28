/*
 * Copyright 2012-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copied from spring-boot v3.5.16 (tag v3.5.16):
 *   spring-boot-project/spring-boot-devtools/src/main/java/org/springframework/boot/devtools/filewatch/SnapshotStateRepository.java
 * Package renamed to com.example.reload.devtools. See reload/NOTICE.
 * Modified: removed the STATIC constant (references a devtools class that was not copied).
 */

package com.example.reload.devtools.filewatch;

/**
 * Repository used by {@link FileSystemWatcher} to save file/directory snapshots across
 * restarts.
 *
 * @author Phillip Webb
 * @since 2.4.0
 */
public interface SnapshotStateRepository {

	/**
	 * A No-op {@link SnapshotStateRepository} that does not save state.
	 */
	SnapshotStateRepository NONE = new SnapshotStateRepository() {

		@Override
		public void save(Object state) {
		}

		@Override
		public Object restore() {
			return null;
		}

	};

	// REMOVED (hot-reload): SnapshotStateRepository STATIC = StaticSnapshotStateRepository.INSTANCE;
	// StaticSnapshotStateRepository is a devtools class that was not copied.

	/**
	 * Save the given state in the repository.
	 * @param state the state to save
	 */
	void save(Object state);

	/**
	 * Restore any previously saved state.
	 * @return the previously saved state or {@code null}
	 */
	Object restore();

}
