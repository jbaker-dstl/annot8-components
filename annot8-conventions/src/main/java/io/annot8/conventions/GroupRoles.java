/* Annot8 (annot8.io) - Licensed under Apache-2.0. */
package io.annot8.conventions;

/** Standard names for common group roles */
public class GroupRoles {
  public static final String GRAMMAR_PREFIX = "grammar" + PathUtils.SEPARATOR;

  public static final String GROUP_ROLE_GRAMMAR_CONSTITUENT = GRAMMAR_PREFIX + "constituent";
  public static final String GROUP_ROLE_GRAMMAR_HEAD = GRAMMAR_PREFIX + "head";
  public static final String GROUP_ROLE_INSTRUMENT = "instrument";
  public static final String GROUP_ROLE_LOCATION = "location";
  public static final String GROUP_ROLE_MENTION = "mention";
  public static final String GROUP_ROLE_OBJECT = "object";
  public static final String GROUP_ROLE_PARTICIPANT = "participant";
  public static final String GROUP_ROLE_PARTOF = "partOf";
  public static final String GROUP_ROLE_SOURCE = "source";
  public static final String GROUP_ROLE_TARGET = "target";

  private GroupRoles() {
    // No constructor - only access to public methods
  }
}
