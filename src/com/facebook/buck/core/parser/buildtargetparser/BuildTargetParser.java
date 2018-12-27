/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.core.parser.buildtargetparser;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.UnknownCellException;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.core.model.impl.ImmutableBuildTarget;
import com.facebook.buck.core.model.impl.ImmutableUnflavoredBuildTarget;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Splitter;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class BuildTargetParser {

  /** The BuildTargetParser is stateless, so this single instance can be shared. */
  public static final BuildTargetParser INSTANCE = new BuildTargetParser();

  private static final String BUILD_RULE_PREFIX = "//";
  private static final String BUILD_RULE_SEPARATOR = ":";
  private static final Splitter BUILD_RULE_SEPARATOR_SPLITTER = Splitter.on(BUILD_RULE_SEPARATOR);

  private final Interner<BuildTarget> flavoredTargetCache = Interners.newWeakInterner();

  private final FlavorParser flavorParser = new FlavorParser();

  private BuildTargetParser() {
    // this is stateless. There's no need to do anything other than grab the instance needed.
  }

  /**
   * @param buildTargetName either a fully-qualified name or relative to the {@link
   *     BuildTargetPatternParser}. For example, inside {@code first-party/orca/orcaapp/BUCK}, which
   *     can be obtained by calling {@code ParseContext.forBaseName("first-party/orca/orcaapp")},
   *     {@code //first-party/orca/orcaapp:assets} and {@code :assets} refer to the same target.
   *     However, from the command line the context is obtained by calling {@link
   *     BuildTargetPatternParser#fullyQualified()} and relative names are not recognized.
   * @param buildTargetPatternParser how targets should be interpreted, such in the context of a
   *     specific build file or only as fully-qualified names (as is the case for targets from the
   *     command line).
   */
  public BuildTarget parse(
      String buildTargetName,
      BuildTargetPatternParser<?> buildTargetPatternParser,
      CellPathResolver cellNames) {

    if (buildTargetName.endsWith(BUILD_RULE_SEPARATOR)
        && !buildTargetPatternParser.isWildCardAllowed()) {
      throw new BuildTargetParseException(
          String.format("%s cannot end with a colon", buildTargetName));
    }

    Optional<String> givenCellName = Optional.empty();
    String targetAfterCell = buildTargetName;
    if (buildTargetName.contains(BUILD_RULE_PREFIX)
        && !buildTargetName.startsWith(BUILD_RULE_PREFIX)) {
      int slashIndex = buildTargetName.indexOf(BUILD_RULE_PREFIX);
      // in order to support Skylark way of referencing repositories but also preserve backwards
      // compatibility, the '@' is simply ignored if it's present
      int repoNameStartIndex = (buildTargetName.charAt(0) == '@') ? 1 : 0;
      givenCellName = Optional.of(buildTargetName.substring(repoNameStartIndex, slashIndex));
      targetAfterCell = buildTargetName.substring(slashIndex);
    }

    if (givenCellName.isPresent() && givenCellName.get().isEmpty()) {
      throw new BuildTargetParseException("Cell name must not be empty.");
    }

    List<String> parts = BUILD_RULE_SEPARATOR_SPLITTER.splitToList(targetAfterCell);
    if (parts.size() != 2) {
      throw new BuildTargetParseException(
          String.format(
              "%s must contain exactly one colon (found %d)", buildTargetName, parts.size() - 1));
    }

    String baseName =
        parts.get(0).isEmpty() ? buildTargetPatternParser.getBaseName() : parts.get(0);
    String shortName = parts.get(1);
    Iterable<String> flavorNames = new HashSet<>();
    int hashIndex = shortName.indexOf('#');
    if (hashIndex != -1 && hashIndex < shortName.length()) {
      flavorNames = flavorParser.parseFlavorString(shortName.substring(hashIndex + 1));
      shortName = shortName.substring(0, hashIndex);
    }

    Objects.requireNonNull(baseName);
    // On Windows, baseName may contain backslashes, which are not permitted by BuildTarget.
    baseName = baseName.replace('\\', '/');
    checkBaseName(baseName, buildTargetName);

    Path cellPath;
    try {
      cellPath = cellNames.getCellPathOrThrow(givenCellName);
    } catch (UnknownCellException e) {
      throw new BuildTargetParseException(
          String.format("When parsing %s: %s", buildTargetName, e.getHumanReadableErrorMessage()));
    }

    ImmutableUnflavoredBuildTarget.Builder unflavoredBuilder =
        ImmutableUnflavoredBuildTarget.builder()
            .setBaseName(baseName)
            .setShortName(shortName)
            // Set the cell path correctly. Because the cellNames comes from the owning cell we can
            // be sure that if this doesn't throw an exception the target cell is visible to the
            // owning cell.
            .setCellPath(cellPath)
            // We are setting the cell name so we can print it later
            .setCell(cellNames.getCanonicalCellName(cellPath));

    UnflavoredBuildTarget unflavoredBuildTarget = unflavoredBuilder.build();
    return flavoredTargetCache.intern(
        ImmutableBuildTarget.of(
            unflavoredBuildTarget,
            RichStream.from(flavorNames).map(InternalFlavor::of).toImmutableSet()));
  }

  protected static void checkBaseName(String baseName, String buildTargetName) {
    if (baseName.equals(BUILD_RULE_PREFIX)) {
      return;
    }
    if (!baseName.startsWith(BUILD_RULE_PREFIX)) {
      throw new BuildTargetParseException(
          String.format("Path in %s must start with %s", buildTargetName, BUILD_RULE_PREFIX));
    }
    int baseNamePathStart = BUILD_RULE_PREFIX.length();
    if (baseName.charAt(baseNamePathStart) == '/') {
      throw new BuildTargetParseException(
          String.format(
              "Build target path should start with an optional cell name, then // and then a "
                  + "relative directory name, not an absolute directory path (found %s)",
              buildTargetName));
    }
    // instead of splitting the path by / and allocating lots of unnecessary garbage, use 2 indices
    // to track the [start, end) indices of the package part
    int start = baseNamePathStart;
    while (start < baseName.length()) {
      int end = baseName.indexOf('/', start);
      if (end == -1) end = baseName.length();
      int len = end - start;
      if (len == 0) {
        throw new BuildTargetParseException(
            String.format(
                "Build target path cannot contain // other than at the start "
                    + "(or after a cell name) (found %s)",
                buildTargetName));
      }
      if (len == 1 && baseName.charAt(start) == '.') {
        throw new BuildTargetParseException(
            String.format("Build target path cannot contain . (found %s)", buildTargetName));
      }
      if (len == 2 && baseName.charAt(start) == '.' && baseName.charAt(start + 1) == '.') {
        throw new BuildTargetParseException(
            String.format("Build target path cannot contain .. (found %s)", buildTargetName));
      }
      start = end + 1;
    }
  }

  /**
   * Converts a string (fully qualified target name) into {@link BuildTarget} objects.
   *
   * @param cellNames - {@link CellPathResolver} to map cell names to paths.
   * @param buildTarget - a fully qualified target-name string.
   * @return - corresponding {@link BuildTarget} object.
   */
  public static BuildTarget fullyQualifiedNameToBuildTarget(
      CellPathResolver cellNames, String buildTarget) {
    return BuildTargetParser.INSTANCE.parse(
        buildTarget, BuildTargetPatternParser.fullyQualified(), cellNames);
  }
}