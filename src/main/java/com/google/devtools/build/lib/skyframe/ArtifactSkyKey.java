// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.Interner;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.concurrent.BlazeInterners;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import java.util.Collection;

/**
 * A utility class for {@link SkyKey}s coming from {@link Artifact}s. Source artifacts are checked
 * for existence, while output artifacts imply creation of the output file.
 *
 * <p>There are effectively three kinds of output artifact values corresponding to these keys. The
 * first corresponds to an ordinary artifact {@link FileArtifactValue}. It stores the relevant data
 * for the artifact -- digest/mtime and size. The second corresponds to either an "aggregating"
 * artifact -- the output of an aggregating middleman action -- or a TreeArtifact. It stores the
 * relevant data of all its inputs, as well as a combined digest for itself.
 */
@Immutable
@ThreadSafe
public final class ArtifactSkyKey {
  private static final Interner<OwnedArtifact> INTERNER = BlazeInterners.newWeakInterner();

  private ArtifactSkyKey() {}

  @ThreadSafe
  public static SkyKey key(Artifact artifact, boolean isMandatory) {
    return INTERNER.intern(
        artifact.isSourceArtifact()
            ? new OwnedArtifact(artifact, isMandatory)
            : new OwnedArtifact(artifact));
  }

  private static final Function<Artifact, SkyKey> TO_MANDATORY_KEY =
      new Function<Artifact, SkyKey>() {
        @Override
        public SkyKey apply(Artifact artifact) {
          return key(artifact, true);
        }
      };

  @ThreadSafe
  public static Iterable<SkyKey> mandatoryKeys(Iterable<Artifact> artifacts) {
    return Iterables.transform(artifacts, TO_MANDATORY_KEY);
  }

  private static final Function<OwnedArtifact, Artifact> TO_ARTIFACT =
      new Function<OwnedArtifact, Artifact>() {
        @Override
        public Artifact apply(OwnedArtifact key) {
          return key.getArtifact();
        }
      };

  public static Collection<Artifact> artifacts(Collection<? extends OwnedArtifact> keys) {
    return Collections2.transform(keys, TO_ARTIFACT);
  }

  public static Artifact artifact(SkyKey key) {
    return TO_ARTIFACT.apply((OwnedArtifact) key.argument());
  }

  public static boolean equalWithOwner(Artifact first, Artifact second) {
    return first.equals(second) && first.getArtifactOwner().equals(second.getArtifactOwner());
  }

  /**
   * Artifacts are compared using just their paths, but in Skyframe, the configured target that owns
   * an artifact must also be part of the comparison. For example, suppose we build //foo:foo in
   * configurationA, yielding artifact foo.out. If we change the configuration to configurationB in
   * such a way that the path to the artifact does not change, requesting foo.out from the graph
   * will result in the value entry for foo.out under configurationA being returned. This would
   * prevent caching the graph in different configurations, and also causes big problems with change
   * pruning, which assumes the invariant that a value's first dependency will always be the same.
   * In this case, the value entry's old dependency on //foo:foo in configurationA would cause it to
   * request (//foo:foo, configurationA) from the graph, causing an undesired re-analysis of
   * (//foo:foo, configurationA).
   *
   * <p>In order to prevent that, instead of using Artifacts as keys in the graph, we use
   * OwnedArtifacts, which compare for equality using both the Artifact, and the owner. The effect
   * is functionally that of making Artifact.equals() check the owner, but only within Skyframe,
   * since outside of Skyframe it is quite crucial that Artifacts with different owners be able to
   * compare equal.
   */
  static class OwnedArtifact implements SkyKey {
    private final Artifact artifact;
    // Always true for derived artifacts.
    private final boolean isMandatory;
    // TODO(janakr): we may want to remove this field in the future. The expensive hash computation
    // is already cached one level down (in the Artifact), so the CPU overhead here may not be
    // worth the memory. However, when running with +CompressedOops, this field is free, so we leave
    // it. When running with -CompressedOops, we might be able to save memory by using polymorphism
    // for isMandatory and dropping this field.
    private int hashCode = 0;

    /** Constructs an OwnedArtifact wrapper for a source artifact. */
    private OwnedArtifact(Artifact sourceArtifact, boolean mandatory) {
      Preconditions.checkArgument(sourceArtifact.isSourceArtifact());
      this.artifact = Preconditions.checkNotNull(sourceArtifact);
      this.isMandatory = mandatory;
    }

    /**
     * Constructs an OwnedArtifact wrapper for a derived artifact. The mandatory attribute is not
     * needed because a derived artifact must be a mandatory input for some action in order to
     * ensure that it is built in the first place. If it fails to build, then that fact is cached in
     * the node, so any action that has it as a non-mandatory input can retrieve that information
     * from the node.
     */
    private OwnedArtifact(Artifact derivedArtifact) {
      this.artifact = Preconditions.checkNotNull(derivedArtifact);
      Preconditions.checkArgument(!derivedArtifact.isSourceArtifact(), derivedArtifact);
      this.isMandatory = true; // Unused.
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.ARTIFACT;
    }

    @Override
    public int hashCode() {
      // We use the hash code caching strategy employed by java.lang.String. There are three subtle
      // things going on here:
      //
      // (1) We use a value of 0 to indicate that the hash code hasn't been computed and cached yet.
      // Yes, this means that if the hash code is really 0 then we will "recompute" it each time.
      // But this isn't a problem in practice since a hash code of 0 should be rare.
      //
      // (2) Since we have no synchronization, multiple threads can race here thinking there are the
      // first one to compute and cache the hash code.
      //
      // (3) Moreover, since 'hashCode' is non-volatile, the cached hash code value written from one
      // thread may not be visible by another. Note that we probably don't need to worry about
      // multiple inefficient reads of 'hashCode' on the same thread since it's non-volatile.
      //
      // All three of these issues are benign from a correctness perspective; in the end we have no
      // overhead from synchronization, at the cost of potentially computing the hash code more than
      // once.
      if (hashCode == 0) {
        hashCode = computeHashCode();
      }
      return hashCode;
    }

    private int computeHashCode() {
      int initialHash = artifact.hashCode() + artifact.getArtifactOwner().hashCode();
      return isMandatory ? initialHash : 47 * initialHash + 1;
    }

    @Override
    public boolean equals(Object that) {
      if (this == that) {
        return true;
      }
      if (!(that instanceof OwnedArtifact)) {
        return false;
      }
      OwnedArtifact thatOwnedArtifact = ((OwnedArtifact) that);
      Artifact thatArtifact = thatOwnedArtifact.artifact;
      return equalWithOwner(artifact, thatArtifact) && isMandatory == thatOwnedArtifact.isMandatory;
    }

    Artifact getArtifact() {
      return artifact;
    }

    /**
     * Returns whether the artifact is a mandatory input of its requesting action. May only be
     * called for source artifacts, since a derived artifact must be a mandatory input of some
     * action in order to have been built in the first place.
     */
    public boolean isMandatory() {
      Preconditions.checkState(artifact.isSourceArtifact(), artifact);
      return isMandatory;
    }

    @Override
    public String toString() {
      return artifact.prettyPrint() + " " + artifact.getArtifactOwner();
    }
  }
}
