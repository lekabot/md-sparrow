/*
 * This file is a part of md-sparrow.
 *
 * Copyright (c) 2026
 * Ivan Karlo <i.karlo@outlook.com> and contributors
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package io.github.yellowhammer.designerxml.cf;

import io.github.yellowhammer.edt.EdtLayout;
import io.github.yellowhammer.edt.EdtObjectReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Права всех ролей на один объект метаданных: чтение одним проходом по ролям.
 *
 * <p>Файл прав хранит отличия от умолчания роли: при включённом «Устанавливать
 * права для новых объектов» записаны снятые права, при выключенном - выданные.
 * Право с ограничениями доступа записано при любом значении, остальные записанные
 * значения, совпадающие с умолчанием, платформа при выгрузке отбрасывает.
 */
public final class ObjectRights {

  private static final String ROLES = "Roles";
  private static final String EDT_RIGHTS = "Rights.rights";

  private ObjectRights() {
  }

  /** Права всех ролей на объект. */
  public static final class Dto {
    /** Имя объекта в файлах прав: {@code Catalog.Товары}. */
    public String object;
    public String kind;
    /** Права вида в порядке платформы. */
    public List<String> rights = new ArrayList<>();
    /** Право -> права, которые платформа выдаёт вместе с ним. */
    public Map<String, List<String>> requires = new LinkedHashMap<>();
    public boolean editable;
    public String readonlyReason;
    public List<RoleDto> roles = new ArrayList<>();
  }

  /** Права одной роли на объект. */
  public static final class RoleDto {
    public String name;
    public String synonym;
    public boolean setForNewObjects;
    /** Действующие права: записанные и взятые из умолчания роли. */
    public List<String> granted = new ArrayList<>();
    /** Ограничения доступа по праву: поля и текст условия. */
    public Map<String, List<Map<String, Object>>> restrictions = new LinkedHashMap<>();
    /** Записанные права подчинённых: реквизитов, команд, табличных частей. */
    public List<ChildDto> children = new ArrayList<>();
    /** Роль закрыта правилами поставки. */
    public String readonlyReason;
  }

  /** Записанные права подчинённого объекта. */
  public static final class ChildDto {
    /** Путь от объекта: {@code Attribute.Цена}. */
    public String name;
    public Map<String, Boolean> rights = new LinkedHashMap<>();
  }

  /** Объект в файлах прав и корень его проекта. */
  private record Target(Path sourceRoot, boolean edt, String object, String kind) {
  }

  public static Dto read(Path objectFile) throws IOException {
    Target target = target(objectFile);
    Dto out = new Dto();
    out.object = target.object();
    out.kind = target.kind();
    RoleRightsCatalog.Kind kind = RoleRightsCatalog.kind(target.kind());
    if (kind != null) {
      out.rights = kind.rights();
      out.requires = kind.requires();
    }
    out.editable = !target.edt() && kind != null;
    if (target.edt()) {
      out.readonlyReason = "В проекте 1С:EDT права можно только посмотреть.";
    }
    for (Path roleFile : roleFiles(target)) {
      out.roles.add(roleRights(target, kind, roleFile));
    }
    return out;
  }

  private static RoleDto roleRights(Target target, RoleRightsCatalog.Kind kind, Path roleFile) throws IOException {
    RoleDto role = new RoleDto();
    role.name = stem(roleFile);
    role.synonym = synonym(roleFile, target.edt());
    Path file = rightsFile(roleFile, target.edt());
    String text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    role.setForNewObjects = RightsText.setForNewObjects(text);
    Map<String, RightsText.Right> own = new LinkedHashMap<>();
    for (RightsText.Block block : RightsText.blocksOf(text, target.object())) {
      if (block.name().equals(target.object())) {
        for (RightsText.Right right : block.rights()) {
          own.put(right.name(), right);
        }
        continue;
      }
      ChildDto child = new ChildDto();
      child.name = block.name().substring(target.object().length() + 1);
      for (RightsText.Right right : block.rights()) {
        child.rights.put(right.name(), right.value());
      }
      role.children.add(child);
    }
    List<String> rights = kind == null ? new ArrayList<>(own.keySet()) : kind.rights();
    for (String right : rights) {
      RightsText.Right stored = own.get(right);
      if (stored == null ? role.setForNewObjects : stored.value()) {
        role.granted.add(right);
      }
      if (stored != null && stored.restricted()) {
        role.restrictions.put(right, RightsText.restrictions(stored.tail()));
      }
    }
    if (!target.edt()) {
      role.readonlyReason = roleLock(roleFile);
    }
    return role;
  }

  private static Target target(Path objectFile) throws IOException {
    Path file = objectFile.toAbsolutePath().normalize();
    boolean edt = EdtLayout.isObjectFile(file);
    Path root = edt ? edtSourceRoot(file) : designerRoot(file);
    if (root == null) {
      throw new IllegalArgumentException("Файл не в каталоге конфигурации: " + objectFile);
    }
    boolean configuration = edt
      ? file.equals(root.resolve("Configuration").resolve("Configuration.mdo"))
      : file.equals(root.resolve("Configuration.xml"));
    if (configuration) {
      String name = edt ? EdtObjectReader.read(file).name() : configurationName(file);
      return new Target(root, edt, "Configuration." + name, "Configuration");
    }
    List<String> parts = new ArrayList<>();
    for (Path part : root.relativize(file)) {
      parts.add(part.toString());
    }
    // У проекта EDT у каждого объекта свой каталог: Catalogs/Товары/Товары.mdo
    if (edt) {
      parts.remove(parts.size() - 1);
    } else {
      parts.set(parts.size() - 1, stem(file));
    }
    StringBuilder object = new StringBuilder();
    String kind = null;
    for (int i = 0; i + 1 < parts.size(); i += 2) {
      String type = kindOfDirectory(parts.get(i));
      if (type == null) {
        throw new IllegalArgumentException("Не объект метаданных: " + objectFile);
      }
      if (kind == null) {
        kind = type;
      }
      if (object.length() > 0) {
        object.append('.');
      }
      object.append(type).append('.').append(parts.get(i + 1));
    }
    if (kind == null) {
      throw new IllegalArgumentException("Не объект метаданных: " + objectFile);
    }
    return new Target(root, edt, object.toString(), kind);
  }

  private static String kindOfDirectory(String directory) {
    for (Map.Entry<String, String> entry : CfObjectPathResolver.subdirsByType().entrySet()) {
      if (entry.getValue().equals(directory)) {
        return entry.getKey();
      }
    }
    return null;
  }

  private static Path designerRoot(Path file) {
    for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
      if (Files.isRegularFile(dir.resolve("Configuration.xml"))) {
        return dir;
      }
    }
    return null;
  }

  private static Path edtSourceRoot(Path file) {
    for (Path dir = file.getParent(); dir != null; dir = dir.getParent()) {
      if (Files.isRegularFile(dir.resolve("Configuration").resolve("Configuration.mdo"))) {
        return dir;
      }
    }
    return null;
  }

  private static String configurationName(Path configurationXml) throws IOException {
    try {
      return ConfigurationObjectNameReader.readName(configurationXml);
    } catch (javax.xml.stream.XMLStreamException e) {
      throw new IOException("Не прочитано имя конфигурации: " + e.getMessage(), e);
    }
  }

  /** Описания ролей конфигурации по имени. */
  private static List<Path> roleFiles(Target target) throws IOException {
    Path roles = target.sourceRoot().resolve(ROLES);
    if (!Files.isDirectory(roles)) {
      return List.of();
    }
    try (Stream<Path> children = Files.list(roles)) {
      if (target.edt()) {
        return children
          .filter(Files::isDirectory)
          .map(dir -> dir.resolve(dir.getFileName() + ".mdo"))
          .filter(Files::isRegularFile)
          .sorted()
          .toList();
      }
      return children
        .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".xml"))
        .sorted()
        .toList();
    }
  }

  private static Path rightsFile(Path roleFile, boolean edt) {
    return edt ? roleFile.getParent().resolve(EDT_RIGHTS) : RoleRightsFile.rightsPath(roleFile);
  }

  /** Почему права роли не правятся: роль на поддержке без изменения или правила поддержки не разобраны. */
  private static String roleLock(Path roleFile) throws IOException {
    try {
      SupportRules.ensureEditable(roleFile);
      return null;
    } catch (IllegalStateException e) {
      if (!"locked".equals(SupportRules.objectState(roleFile))) {
        return e.getMessage();
      }
      return "Роль " + stem(roleFile) + " на поддержке поставщика «" + SupportRules.rulesFor(roleFile).vendor
        + "» без возможности изменения. Включите возможность изменения или снимите роль с поддержки.";
    }
  }

  private static String synonym(Path roleFile, boolean edt) throws IOException {
    if (!edt) {
      return LocalStrings.pick(ObjectHead.read(roleFile).synonym());
    }
    Map<String, String> byLanguage = new LinkedHashMap<>();
    for (EdtObjectReader.EdtNode entry : EdtObjectReader.read(roleFile).list("synonym")) {
      String key = entry.property("key");
      String value = entry.property("value");
      if (!key.isEmpty() && !value.isEmpty()) {
        byLanguage.put(key, value);
      }
    }
    return LocalStrings.pick(byLanguage);
  }

  private static String stem(Path file) {
    return file.getFileName().toString().replaceFirst("[.](xml|mdo)$", "");
  }
}
