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

package com.facebook.buck.rules;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nullable;

/**
 * RuleKey encapsulates regimented computation of SHA-1 keys that incorporate all BuildRule state
 * relevant to idempotency. The RuleKey.Builder API conceptually implements the construction of an
 * ordered map, and the key/val pairs are digested using an internal serialization that guarantees
 * a 1:1 mapping for each distinct vector of keys
 * <ruleLabel,k1,...,kn> in RuleKey.builder(ruleLabel).set(k1, v1) ... .set(kn, vn).build().
 *
 * Note carefully that in order to reliably avoid accidental collisions, each RuleKey schema, as
 * defined by the key vector, must have a distinct ruleLabel. Otherwise it is possible (if unlikely)
 * for serialized value data to alias serialized key data, with the result being identical RuleKeys
 * for differing input. In practical terms this means that each BuildRule implementer should specify
 * a distinct ruleLabel, and that for all RuleKeys built with a particular ruleLabel, the sequence
 * of set() calls should be identical, even if values are missing. The set() methods specifically
 * handle null values to accommodate this regime.
 */
public class RuleKey implements Comparable<RuleKey> {
  @Nullable private final HashCode hashCode;

  private RuleKey(HashCode hashCode) {
    this.hashCode = hashCode;
  }

  public boolean isIdempotent() {
    return (hashCode != null);
  }

  /**
   * Non-idempotent RuleKeys are normally output as strings of 'x' characters, but when comparing
   * two sets of RuleKeys in textual form it is necessary to mangle one of the two sets, so that
   * non-idempotent RuleKeys are never considered equal.
   */
  public String toString(boolean mangleNonIdempotent) {
    if (!isIdempotent()) {
      return new String (new char[Hashing.sha1().bits() / 4]).replace("\0",
          mangleNonIdempotent ? "y" : "x");
    }
    return hashCode.toString();
  }

  @Override
  public String toString() {
    return toString(false);
  }

  /**
   * Order non-idempotent RuleKeys as less than all idempotent RuleKeys.
   *
   * non-idempotent < idempotent
   */
  @Override
  public int compareTo(RuleKey other) {
    if (!isIdempotent()) {
      if (!other.isIdempotent()) {
        return 0;
      }
      return -1;
    } else if (!other.isIdempotent()) {
      return 1;
    }
    return ByteBuffer.wrap(hashCode.asBytes()).compareTo(ByteBuffer.wrap(other.hashCode.asBytes()));
  }

  /**
   * Treat non-idempotent RuleKeys as unequal to everything, including other non-idempotent
   * RuleKeys.
   */
  @Override
  public boolean equals(Object that) {
    if (!(that instanceof RuleKey)) {
      return false;
    }
    RuleKey other = (RuleKey) that;
    if (!isIdempotent() || !other.isIdempotent()) {
      return false;
    }
    return (compareTo(other) == 0);
  }

  @Override
  public int hashCode() {
    if (!isIdempotent()) {
      return 0;
    }
    return super.hashCode();
  }

  /**
   * Helper method used to avoid memoizing non-idempotent RuleKeys.
   */
  @Nullable
  public static RuleKey filter(RuleKey ruleKey) {
    if (!ruleKey.isIdempotent()) {
      return null;
    }
    return ruleKey;
  }

  public static Builder builder(BuildRule rule) {
    return new Builder(rule.getFullyQualifiedName())
    // Keyed as "buck.type" rather than "type" in case a build rule has its own "type" argument.
    .set("buck.type", rule.getType().getDisplayName())
    .setStrings("deps", Iterables.transform(rule.getDeps(), new Function<BuildRule, String>() {
      @Override
      public String apply(BuildRule input) {
        return String.format("%s:%s",
            input.getFullyQualifiedName(),
            input.getType().getDisplayName());
      }
    }));
  }

  public static class Builder {
    private static final byte SEPARATOR = '\0';
    private static final Logger logger = Logger.getLogger(Builder.class.getName());

    private final Hasher hasher;
    private boolean idempotent;
    @Nullable private List<String> logElms;

    private Builder() {
      hasher = Hashing.sha1().newHasher();
      idempotent = true;
      if (logger.isLoggable(Level.INFO)) {
        logElms = Lists.newArrayList();
      }
    }

    private Builder(String ruleLabel) {
      this();
      setHeader(ruleLabel);
    }

    private Builder feed(byte[] bytes) {
      hasher.putBytes(bytes);
      return this;
    }

    private Builder separate() {
      hasher.putByte(SEPARATOR);
      return this;
    }

    private void setHeader(String ruleLabel) {
      if (logElms != null) {
        logElms.add(String.format("header(%s):", ruleLabel));
      }
      feed(ruleLabel.getBytes()).separate();
    }

    private Builder setKey(String sectionLabel) {
      if (logElms != null) {
        logElms.add(String.format(":key(%s):", sectionLabel));
      }
      return separate().feed(sectionLabel.getBytes()).separate();
    }

    private Builder setVal(@Nullable File file) {
      if (file != null) {
        // Compute a separate SHA-1 for the file contents and feed that into messageDigest rather
        // than the file contents, in order to avoid the overhead of escaping SEPARATOR in the file
        // content.
        try {
          byte[] fileBytes = Files.toByteArray(file);
          HashCode fileSha1 = Hashing.sha1().hashBytes(fileBytes);
          if (logElms != null) {
            logElms.add(String.format("file(path=\"%s\", sha1=%s):", file.getPath(),
                fileSha1.toString()));
          }
          feed(fileSha1.asBytes());
        } catch (IOException e) {
          // The file is nonexistent/unreadable; generate a RuleKey that prevents accidental
          // caching.
          if (logElms != null) {
            logElms.add(String.format("file(path=\"%s\", sha1=random):", file.getPath()));
          }
          nonIdempotent();
        }
      }
      return separate();
    }

    private Builder setVal(@Nullable String s) {
      if (s != null) {
        if (logElms != null) {
          logElms.add(String.format("string(\"%s\"):", s));
        }
        feed(s.getBytes());
      }
      return separate();
    }

    private Builder setVal(boolean b) {
      if (logElms != null) {
        logElms.add(String.format("boolean(\"%s\"):", b ? "true" : "false"));
      }
      return feed((b ? "t" : "f").getBytes()).separate();
    }

    private Builder setVal(@Nullable RuleKey ruleKey) {
      if (ruleKey != null) {
        if (logElms != null) {
          logElms.add(String.format("%sruleKey(sha1=%s):",
              ruleKey.isIdempotent() ? "" : "non-idempotent ", ruleKey.toString()));
        }
        feed(ruleKey.toString().getBytes())
            .mergeIdempotence(ruleKey.isIdempotent());
      }
      return separate();
    }

    public Builder set(String key, @Nullable File val) {
      return setKey(key).setVal(val);
    }

    public Builder set(String key, @Nullable String val) {
      return setKey(key).setVal(val);
    }

    public Builder set(String key, Optional<String> val) {
      return set(key, val.isPresent() ? val.get() : null);
    }

    public Builder set(String key, boolean val) {
      return setKey(key).setVal(val);
    }

    public Builder set(String key, @Nullable RuleKey val) {
      return setKey(key).setVal(val);
    }

    public Builder set(String key, @Nullable BuildRule val) {
      return setKey(key).setVal(val != null ? val.getRuleKey() : null);
    }

    public Builder set(String key, @Nullable ImmutableList<SourceRoot> val) {
      setKey(key);
      if (val != null) {
        for (SourceRoot root : val) {
          setVal(root.getName());
        }
      }
      return separate();
    }

    public Builder setStrings(String key, @Nullable Iterable<String> val) {
      setKey(key);
      if (val != null) {
        for (String s : val) {
          setVal(s);
        }
      }
      return separate();
    }

    public Builder set(String key, @Nullable List<String> val) {
      setKey(key);
      if (val != null) {
        for (String s : val) {
          setVal(s);
        }
      }
      return separate();
    }

    public Builder set(String key, @Nullable Iterable<InputRule> val) {
      setKey(key);
      if (val != null) {
        for (InputRule inputRule : val) {
          setVal(inputRule.getRuleKey());
        }
      }
      return separate();
    }

    public Builder set(String key, @Nullable ImmutableSortedSet<BuildRule> val) {
      setKey(key);
      if (val != null) {
        for (BuildRule buildRule :val) {
          setVal(buildRule.getRuleKey());
        }
      }
      return separate();
    }

    public Builder set(String key, @Nullable ImmutableSet<String> val) {
      setKey(key);
      if (val != null) {
        ImmutableSortedSet<String> sortedValues = ImmutableSortedSet.copyOf(val);
        for (String value : sortedValues) {
          setVal(value);
        }
      }
      return separate();
    }

    /**
     * The idempotence of the RuleKey to be built is false if this method is ever called with a
     * false argument.
     */
    public Builder mergeIdempotence(boolean idempotence) {
      if (!idempotence) {
        idempotent = false;
      }
      return this;
    }

    /**
     * Non-idempotent RuleKeys can be built by calling nonIdempotent() at any building stage. This
     * method is intended for generation of RuleKeys to be associated with non-cacheable BuildRule
     * results.
     */
    public Builder nonIdempotent() {
      if (logElms != null) {
        logElms.add(String.format("nonIdempotent()"));
      }
      return mergeIdempotence(false);
    }

    public RuleKey build() {
      RuleKey ruleKey = idempotent ? new RuleKey(hasher.hash()) : new RuleKey(null);
      if (logElms != null) {
        logger.log(Level.INFO, String.format("%sRuleKey %s=%s",
            ruleKey.isIdempotent() ? "" : "non-idempotent ", ruleKey.toString(),
            Joiner.on("").join(logElms)));
      }
      return ruleKey;
    }
  }
}
