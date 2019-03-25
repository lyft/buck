/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.core.files;

import com.facebook.buck.core.graph.transformation.GraphEngineCache;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.watchman.WatchmanOverflowEvent;
import com.facebook.buck.io.watchman.WatchmanPathEvent;
import com.google.common.eventbus.Subscribe;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Stores a recursive file tree */
public class FileTreeCache implements GraphEngineCache<FileTreeKey, FileTree> {

  // TODO(sergeyb): probably use same cache data for DirectoryList and FileTree

  private ConcurrentHashMap<FileTreeKey, FileTree> cache = new ConcurrentHashMap<>();
  private final Path rootPath;
  private final Invalidator invalidator;

  private FileTreeCache(Path rootPath) {
    this.rootPath = rootPath;
    invalidator = new Invalidator(this);
  }

  /**
   * Create a new instance of {@link FileTreeCache}
   *
   * @param rootPath Absolute path to the root folder for which files and subfolders are cached,
   *     this is usually the root path of the cell
   */
  public static FileTreeCache of(Path rootPath) {
    return new FileTreeCache(rootPath);
  }

  @Override
  public Optional<FileTree> get(FileTreeKey key) {
    return Optional.ofNullable(cache.get(key));
  }

  @Override
  public void put(FileTreeKey key, FileTree fileTree) {
    cache.put(key, fileTree);
  }

  /** @return class that listens to watchman events and invalidates internal cache state */
  public Invalidator getInvalidator() {
    return invalidator;
  }

  /**
   * Subscribes to watchman event and invalidates internal state of a provided {@link FileTreeCache}
   */
  public static class Invalidator {
    private final FileTreeCache fileTreeCache;

    public Invalidator(FileTreeCache fileTreeCache) {
      this.fileTreeCache = fileTreeCache;
    }

    /** Invoked asynchronously by event bus when file system change is detected with Watchman */
    @Subscribe
    public void onFileSystemChange(WatchmanPathEvent event) {
      if (event.getKind() == WatchmanPathEvent.Kind.MODIFY) {
        // file modifications do not change directory structure, do nothing
        return;
      }

      if (!fileTreeCache.rootPath.equals(event.getCellPath())) {
        // must be same cell
        return;
      }

      // for CREATE and DELETE, invalidate all folders up the tree
      // TODO(sergeyb): be smarter - modify data in-place instead of full invalidation of the tree
      // this might require to unify FileTreeCache and DirectoryListCache
      Path folderPath = event.getPath().getParent();

      // getParent() usually returns null for first level files and folders, but it depends on
      // implementation, so consider both null and empty path cases
      while (folderPath != null && !folderPath.equals(MorePaths.EMPTY_PATH)) {
        FileTreeKey key = ImmutableFileTreeKey.of(folderPath);
        fileTreeCache.cache.remove(key);
        folderPath = folderPath.getParent();
      }

      // invalidate root explicitly, because getParent() for first level dir returns null
      fileTreeCache.cache.remove(ImmutableFileTreeKey.of(Paths.get("")));
    }

    /**
     * Invoked asynchronously by event bus when Watchman detects too many files changed or unable to
     * detect changes, this should drop the cache
     */
    @Subscribe
    @SuppressWarnings("unused")
    public void onFileSystemChange(WatchmanOverflowEvent event) {
      fileTreeCache.cache = new ConcurrentHashMap<>();
    }
  }
}
