/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.macros.MacroException;
import com.facebook.buck.core.macros.MacroMatchResult;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.rules.args.Arg;
import com.google.common.collect.ImmutableList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An {@link Arg} which contains macros that need to be expanded.
 *
 * <p>Deprecated: Use {@link StringWithMacros} in constructor args and {@link
 * StringWithMacrosConverter} instead.
 */
@Deprecated
public class MacroArg implements Arg, AddsToRuleKey {

  protected final MacroHandler expander;
  protected final BuildTarget target;
  protected final CellPathResolver cellNames;
  protected final ActionGraphBuilder graphBuilder;
  protected final String unexpanded;

  protected Map<MacroMatchResult, Object> precomputedWorkCache = new HashMap<>();
  @AddToRuleKey private final ImmutableList<Object> ruleKeyList;

  public MacroArg(
      MacroHandler expander,
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder,
      String unexpanded) {
    this.expander = expander;
    this.target = target;
    this.cellNames = cellNames;
    this.graphBuilder = graphBuilder;
    this.unexpanded = unexpanded;
    this.ruleKeyList = appendToRuleKey();
  }

  @Override
  public void appendToCommandLine(Consumer<String> consumer, SourcePathResolver pathResolver) {
    try {
      consumer.accept(expander.expand(target, cellNames, graphBuilder, unexpanded));
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
  }

  private ImmutableList<Object> appendToRuleKey() {
    try {
      ImmutableList.Builder<Object> listBuilder = ImmutableList.builder();
      listBuilder.add(unexpanded);
      listBuilder.addAll(
          expander.extractRuleKeyAppendables(
              target, cellNames, graphBuilder, unexpanded, precomputedWorkCache));
      return listBuilder.build();
    } catch (MacroException e) {
      throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
    }
  }

  @Override
  public String toString() {
    return unexpanded;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MacroArg)) {
      return false;
    }
    MacroArg macroArg = (MacroArg) o;
    return Objects.equals(target, macroArg.target)
        && Objects.equals(unexpanded, macroArg.unexpanded);
  }

  @Override
  public int hashCode() {
    return Objects.hash(target, unexpanded);
  }

  public static Function<String, Arg> toMacroArgFunction(
      MacroHandler handler,
      BuildTarget target,
      CellPathResolver cellNames,
      ActionGraphBuilder graphBuilder) {
    return unexpanded -> {
      MacroArg arg = new MacroArg(handler, target, cellNames, graphBuilder, unexpanded);
      try {
        if (containsWorkerMacro(handler, unexpanded)) {
          return WorkerMacroArg.fromMacroArg(
              arg, handler, target, cellNames, graphBuilder, unexpanded);
        }
      } catch (MacroException e) {
        throw new HumanReadableException(e, "%s: %s", target, e.getMessage());
      }
      return arg;
    };
  }

  public static boolean containsWorkerMacro(MacroHandler handler, String blob)
      throws MacroException {
    boolean result = false;
    for (MacroMatchResult matchResult : handler.getMacroMatchResults(blob)) {
      if (handler.getExpander(matchResult.getMacroType()) instanceof WorkerMacroExpander) {
        result = true;
      }
    }
    return result;
  }
}
