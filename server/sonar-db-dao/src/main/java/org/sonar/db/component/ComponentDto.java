/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.db.component;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import java.util.Date;
import java.util.List;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ToStringBuilder;
import org.sonar.api.resources.Scopes;
import org.sonar.db.WildcardPosition;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static org.sonar.db.DaoDatabaseUtils.buildLikeValue;
import static org.sonar.db.component.ComponentValidator.checkComponentKey;
import static org.sonar.db.component.ComponentValidator.checkComponentName;
import static org.sonar.db.component.DbTagsReader.readDbTags;

public class ComponentDto {

  /**
   * Separator used to generate the key of the branch
   */
  public static final String BRANCH_KEY_SEPARATOR = ":BRANCH:";

  private static final Splitter BRANCH_KEY_SPLITTER = Splitter.on(BRANCH_KEY_SEPARATOR);

  public static final String UUID_PATH_SEPARATOR = ".";
  public static final String UUID_PATH_OF_ROOT = UUID_PATH_SEPARATOR;
  private static final Splitter UUID_PATH_SPLITTER = Splitter.on(UUID_PATH_SEPARATOR).omitEmptyStrings();

  static final char TAGS_SEPARATOR = ',';
  private static final Joiner TAGS_JOINER = Joiner.on(TAGS_SEPARATOR).skipNulls();

  /**
   * ID generated by database. Do not use.
   */
  private Long id;

  /**
   * The UUID of the organization the component belongs to. Can't be null in DB.
   */
  private String organizationUuid;

  /**
   * Non-empty and unique functional key
   */
  private String kee;

  /**
   * Not empty . Max size is 50 (note that effective UUID values are 40 characters with
   * the current algorithm in use). Obviously it is unique.
   * It is generated by Compute Engine.
   */
  private String uuid;

  /**
   * Not empty path of ancestor UUIDS, excluding itself. Value is suffixed by a dot in
   * order to support LIKE conditions when requesting descendants of a component
   * and to avoid Oracle NULL on root components.
   * Example:
   * - on root module: UUID="1" UUID_PATH="."
   * - on module: UUID="2" UUID_PATH=".1."
   * - on directory: UUID="3" UUID_PATH=".1.2."
   * - on file: UUID="4" UUID_PATH=".1.2.3."
   * - on view: UUID="5" UUID_PATH="."
   * - on sub-view: UUID="6" UUID_PATH=".5."
   *
   * @since 6.0
   */
  private String uuidPath;

  /**
   * Non-null UUID of root component. Equals UUID column on root components
   * Example:
   * - on root module: UUID="1" PROJECT_UUID="1"
   * - on module: UUID="2" PROJECT_UUID="1"
   * - on directory: UUID="3" PROJECT_UUID="1"
   * - on file: UUID="4" PROJECT_UUID="1"
   * - on view: UUID="5" PROJECT_UUID="5"
   * - on sub-view: UUID="6" PROJECT_UUID="5"
  */
  private String projectUuid;

  /**
   * Badly named, it is not the root !
   * - on root module: UUID="1" ROOT_UUID="1"
   * - on modules, whatever depth, value is the root module: UUID="2" ROOT_UUID="1"
   * - on directory, value is the closest module: UUID="3" ROOT_UUID="2"
   * - on file, value is the closest module: UUID="4" ROOT_UUID="2"
   * - on view: UUID="5" ROOT_UUID="5"
   * - on sub-view: UUID="6" ROOT_UUID="5"
   * @since 6.0
   */
  private String rootUuid;

  /**
   * On non-main branches only, {@link #uuid} of the main branch that represents
   * the project ({@link #qualifier}="TRK").x
   * It is propagated to all the components of the branch.
   *
   * Value is null on the main-branch components and on other kinds of components
   * (applications, portfolios).
   *
   * Value must be used for loading settings, checking permissions, running webhooks,
   * selecting Quality profiles/gates and any other project-related operations.
   *
   * Example:
   * - project P : kee=P, uuid=U1, qualifier=TRK, project_uuid=U1, main_branch_project_uuid=NULL
   * - file F of project P : kee=P:F, uuid=U2, qualifier=FIL, project_uuid=U1, main_branch_project_uuid=NULL
   * - branch B of project P : kee=P:BRANCH:B, uuid=U3, qualifier=TRK, project_uuid=U3, main_branch_project_uuid=U1
   * - file F in branch B of project P : kee=P:F:BRANCH:B, uuid=U4, qualifier=FIL, project_uuid=U3, main_branch_project_uuid=U1
   */
  @Nullable
  private String mainBranchProjectUuid;

  private String moduleUuid;
  private String moduleUuidPath;
  private String copyComponentUuid;
  private String scope;
  private String qualifier;
  private String path;
  private String deprecatedKey;
  private String name;
  private String longName;
  private String language;
  private String description;
  private String tags;
  private boolean enabled = true;
  private boolean isPrivate = false;

  private Date createdAt;

  public static String formatUuidPathFromParent(ComponentDto parent) {
    checkArgument(!Strings.isNullOrEmpty(parent.getUuidPath()));
    checkArgument(!Strings.isNullOrEmpty(parent.uuid()));
    return parent.getUuidPath() + parent.uuid() + UUID_PATH_SEPARATOR;
  }

  public String getUuidPathLikeIncludingSelf() {
    return buildLikeValue(formatUuidPathFromParent(this), WildcardPosition.AFTER);
  }

  public Long getId() {
    return id;
  }

  public ComponentDto setId(Long id) {
    this.id = id;
    return this;
  }

  public String getOrganizationUuid() {
    return organizationUuid;
  }

  public ComponentDto setOrganizationUuid(String organizationUuid) {
    this.organizationUuid = organizationUuid;
    return this;
  }

  public String uuid() {
    return uuid;
  }

  public ComponentDto setUuid(String uuid) {
    this.uuid = uuid;
    return this;
  }

  public String getUuidPath() {
    return uuidPath;
  }

  public ComponentDto setUuidPath(String s) {
    this.uuidPath = s;
    return this;
  }

  /**
   * List of ancestor UUIDs, ordered by depth in tree.
   */
  public List<String> getUuidPathAsList() {
    return UUID_PATH_SPLITTER.splitToList(uuidPath);
  }

  /**
   * Used my MyBatis mapper
   */
  private String getKee(){
    return kee;
  }

  /**
   * Used my MyBatis mapper
   */
  private void setKee(String kee){
    this.kee = kee;
  }

  public String getDbKey() {
    return kee;
  }

  public ComponentDto setDbKey(String key) {
    this.kee = checkComponentKey(key);
    return this;
  }

  /**
   * The key to be displayed to user, doesn't contain information on branches
   */
  public String getKey() {
    List<String> split = BRANCH_KEY_SPLITTER.splitToList(kee);
    return split.size() == 2 ? split.get(0) : kee;
  }

  /**
   * @return the key of the branch. It will be null on the main branch and when the component is not on a branch
   */
  @CheckForNull
  public String getBranch() {
    List<String> split = BRANCH_KEY_SPLITTER.splitToList(kee);
    return split.size() == 2 ? split.get(1) : null;
  }

  public String scope() {
    return scope;
  }

  public ComponentDto setScope(String scope) {
    this.scope = scope;
    return this;
  }

  public String qualifier() {
    return qualifier;
  }

  public ComponentDto setQualifier(String qualifier) {
    this.qualifier = qualifier;
    return this;
  }

  @CheckForNull
  public String deprecatedKey() {
    return deprecatedKey;
  }

  public ComponentDto setDeprecatedKey(@Nullable String deprecatedKey) {
    this.deprecatedKey = deprecatedKey;
    return this;
  }

  /**
   * Return the root project uuid. On a root project, return itself
   */
  public String projectUuid() {
    return projectUuid;
  }

  public ComponentDto setProjectUuid(String projectUuid) {
    this.projectUuid = projectUuid;
    return this;
  }

  public boolean isRoot() {
    return UUID_PATH_OF_ROOT.equals(uuidPath);
  }

  /**
   * Return the direct module of a component. Will be null on projects
   */
  @CheckForNull
  public String moduleUuid() {
    return moduleUuid;
  }

  public ComponentDto setModuleUuid(@Nullable String moduleUuid) {
    this.moduleUuid = moduleUuid;
    return this;
  }

  /**
   * Return the path from the project to the last modules
   */
  public String moduleUuidPath() {
    return moduleUuidPath;
  }

  public ComponentDto setModuleUuidPath(String moduleUuidPath) {
    this.moduleUuidPath = moduleUuidPath;
    return this;
  }

  @CheckForNull
  public String path() {
    return path;
  }

  public ComponentDto setPath(@Nullable String path) {
    this.path = path;
    return this;
  }

  public String name() {
    return name;
  }

  public ComponentDto setName(String name) {
    this.name = checkComponentName(name);
    return this;
  }

  public String longName() {
    return longName;
  }

  public ComponentDto setLongName(String longName) {
    this.longName = longName;
    return this;
  }

  @CheckForNull
  public String language() {
    return language;
  }

  public ComponentDto setLanguage(@Nullable String language) {
    this.language = language;
    return this;
  }

  @CheckForNull
  public String description() {
    return description;
  }

  public ComponentDto setDescription(@Nullable String description) {
    this.description = description;
    return this;
  }

  /**
   * Use {@link #projectUuid()}, {@link #moduleUuid()} or {@link #moduleUuidPath()}
   */
  @Deprecated
  public String getRootUuid() {
    return rootUuid;
  }

  public ComponentDto setRootUuid(String rootUuid) {
    this.rootUuid = rootUuid;
    return this;
  }

  @Nullable
  public String getMainBranchProjectUuid() {
    return mainBranchProjectUuid;
  }

  public ComponentDto setMainBranchProjectUuid(@Nullable String s) {
    this.mainBranchProjectUuid = s;
    return this;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public ComponentDto setEnabled(boolean enabled) {
    this.enabled = enabled;
    return this;
  }

  @CheckForNull
  public String getCopyResourceUuid() {
    return copyComponentUuid;
  }

  public ComponentDto setCopyComponentUuid(@Nullable String copyComponentUuid) {
    this.copyComponentUuid = copyComponentUuid;
    return this;
  }

  public Date getCreatedAt() {
    return createdAt;
  }

  public ComponentDto setCreatedAt(Date datetime) {
    this.createdAt = datetime;
    return this;
  }

  public boolean isRootProject() {
    return moduleUuid == null && Scopes.PROJECT.equals(scope);
  }

  public List<String> getTags() {
    return readDbTags(tags);
  }

  public ComponentDto setTags(List<String> tags) {
    setTagsString(TAGS_JOINER.join(tags));
    return this;
  }

  /**
   * Used by MyBatis
   */
  @CheckForNull
  public String getTagsString() {
    return tags;
  }

  public ComponentDto setTagsString(@Nullable String tags) {
    this.tags = tags;
    return this;
  }

  public boolean isPrivate() {
    return isPrivate;
  }

  public ComponentDto setPrivate(boolean flag) {
    this.isPrivate = flag;
    return this;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ComponentDto that = (ComponentDto) o;
    return uuid != null ? uuid.equals(that.uuid) : (that.uuid == null);

  }

  @Override
  public int hashCode() {
    return uuid != null ? uuid.hashCode() : 0;
  }

  @Override
  public String toString() {
    return new ToStringBuilder(this)
      .append("id", id)
      .append("uuid", uuid)
      .append("uuidPath", uuidPath)
      .append("kee", kee)
      .append("scope", scope)
      .append("qualifier", qualifier)
      .append("projectUuid", projectUuid)
      .append("moduleUuid", moduleUuid)
      .append("moduleUuidPath", moduleUuidPath)
      .append("rootUuid", rootUuid)
      .append("mainBranchProjectUuid", mainBranchProjectUuid)
      .append("copyComponentUuid", copyComponentUuid)
      .append("path", path)
      .append("deprecatedKey", deprecatedKey)
      .append("name", name)
      .append("longName", longName)
      .append("language", language)
      .append("enabled", enabled)
      .append("private", isPrivate)
      .toString();
  }

  public ComponentDto copy() {
    ComponentDto copy = new ComponentDto();
    copy.projectUuid = projectUuid;
    copy.id = id;
    copy.organizationUuid = organizationUuid;
    copy.kee = kee;
    copy.uuid = uuid;
    copy.uuidPath = uuidPath;
    copy.projectUuid = projectUuid;
    copy.rootUuid = rootUuid;
    copy.mainBranchProjectUuid = mainBranchProjectUuid;
    copy.moduleUuid = moduleUuid;
    copy.moduleUuidPath = moduleUuidPath;
    copy.copyComponentUuid = copyComponentUuid;
    copy.scope = scope;
    copy.qualifier = qualifier;
    copy.path = path;
    copy.deprecatedKey = deprecatedKey;
    copy.name = name;
    copy.longName = longName;
    copy.language = language;
    copy.description = description;
    copy.tags = tags;
    copy.enabled = enabled;
    copy.isPrivate = isPrivate;
    copy.createdAt = createdAt;
    return copy;
  }

  public static String generateBranchKey(String componentKey, String branch) {
    return format("%s%s%s", componentKey, BRANCH_KEY_SEPARATOR, branch);
  }

  public static String removeBranchFromKey(String componentKey) {
    return StringUtils.substringBeforeLast(componentKey, ComponentDto.BRANCH_KEY_SEPARATOR);
  }

}
