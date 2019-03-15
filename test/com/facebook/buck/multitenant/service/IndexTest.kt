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

package com.facebook.buck.multitenant.service

import com.facebook.buck.core.cell.impl.ImmutableDefaultCellPathResolver
import com.facebook.buck.core.model.BuildTarget
import com.facebook.buck.core.model.EmptyTargetConfiguration
import com.facebook.buck.core.parser.buildtargetparser.ParsingUnconfiguredBuildTargetFactory
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.assertEquals

class IndexTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    var buildTargetParser: ((target: String) -> BuildTarget)? = null

    @Before
    fun setUp() {
        val factory = ParsingUnconfiguredBuildTargetFactory()
        val cellPathResolver = ImmutableDefaultCellPathResolver.of(temporaryFolder.root.toPath(),
                emptyMap<String, Path>())
        buildTargetParser = {
            val unconfiguredBuildTarget = factory.create(cellPathResolver, it)
            unconfiguredBuildTarget.configure(EmptyTargetConfiguration.INSTANCE)
        }
    }

    @Test
    fun getTargetsAndDeps() {
        val bt = requireNotNull(buildTargetParser)
        val index = Index(bt)
        /*
         * //java/com/facebook/buck/base:base has no deps.
         */
        val changes1 = Changes(
                addedBuildPackages = listOf(
                        BuildPackage(Paths.get("java/com/facebook/buck/base"),
                                mapOf(
                                        "base" to setOf()
                                ))
                ),
                modifiedBuildPackages = listOf(),
                removedBuildPackages = listOf())
        val commit1 = "608fd7bdf9"
        index.addCommitData(commit1, changes1)

        /*
         * //java/com/facebook/buck/model:model depends on //java/com/facebook/buck/base:base.
         */
        val changes2 = Changes(
                addedBuildPackages = listOf(
                        BuildPackage(Paths.get("java/com/facebook/buck/model"),
                                mapOf(
                                        "model" to setOf(
                                                bt("//java/com/facebook/buck/base:base")
                                        )
                                ))
                ),
                modifiedBuildPackages = listOf(),
                removedBuildPackages = listOf())
        val commit2 = "9efba3bca1"
        index.addCommitData(commit2, changes2)

        /*
         * //java/com/facebook/buck/util:util is introduced and
         * //java/com/facebook/buck/model:model is updated to depend on it.
         */
        val changes3 = Changes(
                addedBuildPackages = listOf(
                        BuildPackage(Paths.get("java/com/facebook/buck/util"),
                                mapOf(
                                        "util" to setOf(
                                                bt("//java/com/facebook/buck/base:base")
                                        )
                                ))
                ),
                modifiedBuildPackages = listOf(
                        BuildPackage(Paths.get("java/com/facebook/buck/model"),
                                mapOf(
                                        "model" to setOf(
                                                bt("//java/com/facebook/buck/base:base"),
                                                bt("//java/com/facebook/buck/util:util")
                                        )
                                ))
                ),
                removedBuildPackages = listOf())
        val commit3 = "1b522b5b47"
        index.addCommitData(commit3, changes3)

        /* Nothing changes! */
        val changes4 = Changes(listOf(), listOf(), listOf())
        val commit4 = "270c3e4c42"
        index.addCommitData(commit4, changes4)

        /*
         * //java/com/facebook/buck/model:model is removed.
         */
        val changes5 = Changes(
                addedBuildPackages = listOf(),
                modifiedBuildPackages = listOf(),
                removedBuildPackages = listOf(Paths.get("java/com/facebook/buck/model")))
        val commit5 = "c880d5b5d8"
        index.addCommitData(commit5, changes5)

        index.acquireReadLock().use {
            assertEquals(
                    setOf(bt("//java/com/facebook/buck/base:base")),
                    index.getTargets(it, commit1).toSet())
            assertEquals(
                    setOf(
                            bt("//java/com/facebook/buck/base:base"),
                            bt("//java/com/facebook/buck/model:model")),
                    index.getTargets(it, commit2).toSet())
            assertEquals(
                    setOf(
                            bt("//java/com/facebook/buck/base:base"),
                            bt("//java/com/facebook/buck/model:model"),
                            bt("//java/com/facebook/buck/util:util")),
                    index.getTargets(it, commit3).toSet())
            assertEquals(
                    setOf(
                            bt("//java/com/facebook/buck/base:base"),
                            bt("//java/com/facebook/buck/model:model"),
                            bt("//java/com/facebook/buck/util:util")),
                    index.getTargets(it, commit4).toSet())
            assertEquals(
                    setOf(
                            bt("//java/com/facebook/buck/base:base"),
                            bt("//java/com/facebook/buck/util:util")),
                    index.getTargets(it, commit5).toSet())

            assertEquals(
                    setOf(
                            bt("//java/com/facebook/buck/base:base")
                    ),
                    index.getTransitiveDeps(it, commit2, bt("//java/com/facebook/buck/model:model"))
            )
            assertEquals(
                    setOf(
                            bt("//java/com/facebook/buck/base:base"),
                            bt("//java/com/facebook/buck/util:util")
                    ),
                    index.getTransitiveDeps(it, commit3, bt("//java/com/facebook/buck/model:model"))
            )
        }
    }
}
