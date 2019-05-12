/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.android.aapt;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ComparisonChain;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** Represents a row from a symbols file generated by {@code aapt}. */
public class RDotTxtEntry implements Comparable<RDotTxtEntry> {

  public enum CustomDrawableType {
    NONE,
    CUSTOM,
    GRAYSCALE_IMAGE,
  }

  // Taken from http://developer.android.com/reference/android/R.html
  public enum RType {
    ANIM,
    ANIMATOR,
    ARRAY,
    ATTR,
    BOOL,
    COLOR,
    DIMEN,
    DRAWABLE,
    FRACTION,
    FONT,
    ID,
    INTEGER,
    INTERPOLATOR,
    LAYOUT,
    MENU,
    NAVIGATION,
    MIPMAP,
    PLURALS,
    RAW,
    STRING,
    STYLE,
    STYLEABLE,
    TRANSITION,
    XML;

    @Override
    public String toString() {
      return super.toString().toLowerCase();
    }
  }

  public enum IdType {
    INT,
    INT_ARRAY;

    public static IdType from(String raw) {
      if (raw.equals("int")) {
        return INT;
      } else if (raw.equals("int[]")) {
        return INT_ARRAY;
      }
      throw new IllegalArgumentException(String.format("'%s' is not a valid ID type.", raw));
    }

    @Override
    public String toString() {
      if (this.equals(INT)) {
        return "int";
      }
      return "int[]";
    }
  }

  public static final Function<String, RDotTxtEntry> TO_ENTRY =
      input -> {
        Optional<RDotTxtEntry> entry = parse(input);
        Preconditions.checkState(entry.isPresent(), "Could not parse R.txt entry: '%s'", input);
        return entry.get();
      };

  // An identifier for custom drawables.
  public static final String CUSTOM_DRAWABLE_IDENTIFIER = "#";
  public static final String GRAYSCALE_IMAGE_IDENTIFIER = "G";
  public static final String INT_ARRAY_SEPARATOR = ",";
  private static final Pattern INT_ARRAY_VALUES = Pattern.compile("\\s*\\{\\s*(\\S+)?\\s*\\}\\s*");
  private static final Pattern TEXT_SYMBOLS_LINE =
      Pattern.compile(
          "(\\S+) (\\S+) (\\S+) ([^("
              + CUSTOM_DRAWABLE_IDENTIFIER
              + "|"
              + GRAYSCALE_IMAGE_IDENTIFIER
              + ")]+)"
              + "( ("
              + CUSTOM_DRAWABLE_IDENTIFIER
              + "|"
              + GRAYSCALE_IMAGE_IDENTIFIER
              + "))?");

  // A symbols file may look like:
  //
  //    int id placeholder 0x7f020000
  //    int string debug_http_proxy_dialog_title 0x7f030004
  //    int string debug_http_proxy_hint 0x7f030005
  //    int string debug_http_proxy_summary 0x7f030003
  //    int string debug_http_proxy_title 0x7f030002
  //    int string debug_ssl_cert_check_summary 0x7f030001
  //    int string debug_ssl_cert_check_title 0x7f030000
  //
  // Note that there are four columns of information:
  // - the type of the resource id (always seems to be int or int[], in practice)
  // - the type of the resource
  // - the name of the resource
  // - the value of the resource id
  //
  // Note that styleable attributes (f.i. "int styleable Anchor_Layout_android_gravity") should be
  // grouped together based on parent name ("Anchor_Layout" in example above). Since due to
  // naming flexibility, it is not possible to infer parent name from attribute name. That's why
  // we decided to introduce optional "parent" field which will keep information about parent, so
  // later we can properly sort all attributes. At the moment this field only used by IdType#INT
  // RType#STYLEABLE attributes. To make "compareTo" logic simpler, if parent is "null" - it will be
  // set to "name" value
  //
  // Custom drawables will have an additional column to denote them.
  //    int drawable custom_drawable 0x07f01250 #
  public final IdType idType;
  public final RType type;
  public final String name;
  public final String idValue;
  public final String parent;
  public final CustomDrawableType customType;

  public RDotTxtEntry(IdType idType, RType type, String name, String idValue) {
    this(idType, type, name, idValue, CustomDrawableType.NONE);
  }

  public RDotTxtEntry(
      IdType idType, RType type, String name, String idValue, @Nullable String parent) {
    this(idType, type, name, idValue, CustomDrawableType.NONE, parent);
  }

  public RDotTxtEntry(
      IdType idType, RType type, String name, String idValue, CustomDrawableType customType) {
    this(idType, type, name, idValue, customType, name);
  }

  public RDotTxtEntry(
      IdType idType,
      RType type,
      String name,
      String idValue,
      CustomDrawableType customType,
      @Nullable String parent) {
    this.idType = idType;
    this.type = type;
    this.name = name;
    this.idValue = hexDecimalStringValue(idType, type, idValue);
    this.customType = customType;
    this.parent = parent != null ? parent : name;
  }

  /*
   * Convert integer string values to hex decimal string for
   * non integer arrays and non styleable entries.
   */
  private static String hexDecimalStringValue(IdType idType, RType type, String idValue) {
    if (idType == IdType.INT_ARRAY || type == RType.STYLEABLE) {
      return idValue;
    }
    if (idValue.length() == 0 || idValue.startsWith("0x")) {
      return idValue;
    } else {
      return String.format("0x%08x", Integer.parseInt(idValue));
    }
  }

  public int getNumArrayValues() {
    Preconditions.checkState(idType == IdType.INT_ARRAY);

    Matcher matcher = INT_ARRAY_VALUES.matcher(idValue);
    if (!matcher.matches() || matcher.group(1) == null) {
      return 0;
    }

    return matcher.group(1).split(INT_ARRAY_SEPARATOR).length;
  }

  public RDotTxtEntry copyWithNewIdValue(String newIdValue) {
    return new RDotTxtEntry(idType, type, name, newIdValue, customType, parent);
  }

  public RDotTxtEntry copyWithNewParent(String parent) {
    return new RDotTxtEntry(idType, type, name, idValue, customType, parent);
  }

  public static Optional<RDotTxtEntry> parse(String rDotTxtLine) {
    Matcher matcher = TEXT_SYMBOLS_LINE.matcher(rDotTxtLine);
    if (!matcher.matches()) {
      return Optional.empty();
    }

    CustomDrawableType customType = CustomDrawableType.NONE;
    IdType idType = IdType.from(matcher.group(1));
    RType type = RType.valueOf(matcher.group(2).toUpperCase());
    String name = matcher.group(3);
    String idValue = matcher.group(4);
    String custom = matcher.group(5);

    if (custom != null && custom.length() > 0) {
      custom = matcher.group(6);
    }

    if (CUSTOM_DRAWABLE_IDENTIFIER.equals(custom)) {
      customType = CustomDrawableType.CUSTOM;
    } else if (GRAYSCALE_IMAGE_IDENTIFIER.equals(custom)) {
      customType = CustomDrawableType.GRAYSCALE_IMAGE;
    }

    return Optional.of(new RDotTxtEntry(idType, type, name, idValue, customType));
  }

  /**
   * Read resource IDs from a R.txt file and add them to a list of entries
   *
   * @param owningFilesystem The project filesystem to use
   * @param rDotTxt the path to the R.txt file to read
   * @return a list of RDotTxtEntry objects read from the file
   * @throws IOException
   */
  public static List<RDotTxtEntry> readResources(ProjectFilesystem owningFilesystem, Path rDotTxt)
      throws IOException {
    return owningFilesystem.readLines(rDotTxt).stream()
        .filter(input -> !Strings.isNullOrEmpty(input))
        .map(RDotTxtEntry.TO_ENTRY)
        .collect(Collectors.toList());
  }

  /**
   * A collection of Resources should be sorted such that Resources of the same type should be
   * grouped together, and should be alphabetized within that group.
   */
  @Override
  public int compareTo(RDotTxtEntry that) {
    if (this == that) {
      return 0;
    }

    ComparisonChain comparisonChain =
        ComparisonChain.start()
            .compare(this.type, that.type)
            .compare(this.parent, that.parent)
            .compare(this.name, that.name);

    return comparisonChain.result();
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof RDotTxtEntry)) {
      return false;
    }

    RDotTxtEntry that = (RDotTxtEntry) obj;
    return Objects.equal(this.type, that.type) && Objects.equal(this.name, that.name);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(type, name);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(RDotTxtEntry.class)
        .add("idType", idType)
        .add("type", type)
        .add("name", name)
        .add("idValue", idValue)
        .add("parent", parent)
        .toString();
  }
}
