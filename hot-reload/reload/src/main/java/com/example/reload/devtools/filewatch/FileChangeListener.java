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
 *   spring-boot-project/spring-boot-devtools/src/main/java/org/springframework/boot/devtools/filewatch/FileChangeListener.java
 * Package renamed to com.example.reload.devtools. See reload/NOTICE.
 */

package com.example.reload.devtools.filewatch;

import java.util.Set;

/**
 * Callback interface when file changes are detected.
 *
 * @author Andy Clement
 * @author Phillip Webb
 * @since 1.3.0
 */
@FunctionalInterface
public interface FileChangeListener {

	/**
	 * Called when files have been changed.
	 * @param changeSet a set of the {@link ChangedFiles}
	 */
	void onChange(Set<ChangedFiles> changeSet);

}
