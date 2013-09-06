/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.jimfs.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.jimfs.internal.LinkHandling.FOLLOW_LINKS;

import com.google.common.collect.Iterables;
import com.google.jimfs.internal.file.DirectoryTable;
import com.google.jimfs.internal.file.File;
import com.google.jimfs.internal.file.TargetPath;
import com.google.jimfs.internal.path.JimfsPath;
import com.google.jimfs.internal.path.Name;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.annotation.Nullable;

/**
 * Service handling file lookup for a {@link FileTree}.
 *
 * @author Colin Decker
 */
public final class LookupService {

  private static final int MAX_SYMBOLIC_LINK_DEPTH = 10;

  /**
   * Looks up the file key for the given absolute path.
   */
  public LookupResult lookup(
      FileTree tree, JimfsPath path, LinkHandling linkHandling) throws IOException {
    checkNotNull(path);
    checkNotNull(linkHandling);

    File base;
    if (path.isAbsolute()) {
      base = tree.getSuperRoot().base();
    } else {
      base = tree.base();
      if (isEmpty(path)) {
        // empty path is equivalent to "." in a lookup
        path = path.getFileSystem().getPath(".");
      }
    }

    tree.readLock().lock();
    try {
      return lookup(tree.getSuperRoot(), base, toNames(path), linkHandling, 0);
    } finally {
      tree.readLock().unlock();
    }
  }

  /**
   * Looks up the file key for the given path.
   */
  private LookupResult lookup(
      FileTree superRoot, File dir, JimfsPath path, LinkHandling linkHandling, int linkDepth)
      throws IOException {
    if (path.isAbsolute()) {
      dir = superRoot.base();
    } else if (isEmpty(path)) {
      // empty path is equivalent to "." in a lookup
      path = path.getFileSystem().getPath(".");
    }

    checkNotNull(linkHandling);
    return lookup(superRoot, dir, toNames(path), linkHandling, linkDepth);
  }

  /**
   * Looks up the given names against the given base file. If the file is not a directory, the
   * lookup fails.
   */
  private LookupResult lookup(FileTree superRoot, @Nullable File dir,
      Deque<Name> names, LinkHandling linkHandling, int linkDepth) throws IOException {
    Name name = names.removeFirst();
    while (!names.isEmpty()) {
      DirectoryTable table = getDirectoryTable(dir);
      File file = table == null ? null : table.get(name);

      if (file != null && file.isSymbolicLink()) {
        LookupResult linkResult = followSymbolicLink(superRoot, table, file, linkDepth);

        if (!linkResult.found()) {
          return LookupResult.notFound();
        }

        dir = linkResult.file();
      } else {
        dir = file;
      }

      name = names.removeFirst();
    }

    return lookupLast(superRoot, dir, name, linkHandling, linkDepth);
  }

  /**
   * Looks up the last element of a path.
   */
  private LookupResult lookupLast(FileTree superRoot, File dir,
      Name name, LinkHandling linkHandling, int linkDepth) throws IOException {
    DirectoryTable table = getDirectoryTable(dir);
    if (table == null) {
      return LookupResult.notFound();
    }

    File file = table.get(name);
    if (file == null) {
      return LookupResult.parentFound(dir);
    }

    if (linkHandling == FOLLOW_LINKS && file.isSymbolicLink()) {
      // TODO(cgdecker): can add info on the symbolic link and its parent here if needed
      // for now it doesn't seem like it's needed though
      return followSymbolicLink(superRoot, table, file, linkDepth);
    }

    return LookupResult.found(dir, file, table.canonicalize(name));
  }

  private LookupResult followSymbolicLink(
      FileTree superRoot, DirectoryTable table, File link, int linkDepth) throws IOException {
    if (linkDepth >= MAX_SYMBOLIC_LINK_DEPTH) {
      throw new IOException("too many levels of symbolic links");
    }

    TargetPath targetPath = link.content();
    return lookup(superRoot, table.self(), targetPath.path(), FOLLOW_LINKS, linkDepth + 1);
  }

  @Nullable
  private DirectoryTable getDirectoryTable(@Nullable File file) {
    if (file != null && file.isDirectory()) {
      return file.content();
    }

    return null;
  }

  private static Deque<Name> toNames(JimfsPath path) {
    Deque<Name> names = new ArrayDeque<>();
    Iterables.addAll(names, path.allNames());
    return names;
  }

  /**
   * Returns true if path has no root component (is not absolute) and either has no name
   * components or only has a single name component, the empty string.
   */
  private static boolean isEmpty(JimfsPath path) {
    return !path.isAbsolute() && (path.getNameCount() == 0
        || path.getNameCount() == 1 && path.getName(0).toString().equals(""));
  }
}
