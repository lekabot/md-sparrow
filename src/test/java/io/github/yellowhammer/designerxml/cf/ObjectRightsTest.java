/*
 * This file is a part of md-sparrow.
 *
 * Copyright (c) 2026
 * Ivan Karlo <i.karlo@outlook.com> and contributors
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package io.github.yellowhammer.designerxml.cf;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Права ролей на объект.
 *
 * <p>Фикстура {@code object-rights}: {@code cf} выгрузил конфигуратор, {@code edt} получен
 * импортом той же выгрузки в 1С:EDT.
 */
class ObjectRightsTest {

  private static final Path FIXTURE = Path.of("src", "test", "resources", "object-rights").toAbsolutePath();
  private static final List<String> ROLES = List.of("Администратор", "Гость", "Кладовщик");
  private static final List<String> DELETE = List.of(
    "Delete",
    "InteractiveDelete",
    "InteractiveDeleteMarked",
    "InteractiveDeletePredefinedData",
    "InteractiveDeleteMarkedPredefinedData");

  @Test
  void readsRightsOfEveryRoleOnObject() throws Exception {
    ObjectRights.Dto dto = ObjectRights.read(FIXTURE.resolve("cf/Catalogs/Товары.xml"));

    assertThat(dto.object).isEqualTo("Catalog.Товары");
    assertThat(dto.kind).isEqualTo("Catalog");
    assertThat(dto.rights).isEqualTo(RoleRightsCatalog.kind("Catalog").rights());
    assertThat(dto.requires).containsKey("InteractiveInsert");
    assertThat(dto.editable).isTrue();
    assertThat(dto.readonlyReason).isNull();
    assertThat(dto.roles).extracting(role -> role.name).containsExactlyElementsOf(ROLES);

    ObjectRights.RoleDto admin = role(dto, "Администратор");
    assertThat(admin.setForNewObjects).isTrue();
    List<String> notDeleting = new ArrayList<>(dto.rights);
    notDeleting.removeAll(DELETE);
    assertThat(admin.granted).containsExactlyElementsOf(notDeleting);

    assertThat(role(dto, "Гость").granted).isEmpty();

    ObjectRights.RoleDto storekeeper = role(dto, "Кладовщик");
    assertThat(storekeeper.synonym).isEqualTo("Кладовщик склада");
    assertThat(storekeeper.setForNewObjects).isFalse();
    assertThat(storekeeper.granted).containsExactly("Read", "View");
    assertThat(storekeeper.readonlyReason).isNull();
  }

  @Test
  void readsRestrictionsAndChildRightsWithoutNeighbourObjects() throws Exception {
    ObjectRights.RoleDto storekeeper = role(ObjectRights.read(FIXTURE.resolve("cf/Catalogs/Товары.xml")), "Кладовщик");

    assertThat(storekeeper.restrictions).containsOnlyKeys("Read");
    List<Map<String, Object>> read = storekeeper.restrictions.get("Read");
    assertThat(read).hasSize(2);
    assertThat(read.get(0).get("fields")).isEqualTo(List.of());
    assertThat(read.get(0).get("condition")).isEqualTo(
      "#Если &ОграничениеПоСкладу #Тогда\n#ПоЗначениям(\"Справочник.Товары\", \"\", \"\", \"Склады\", \"Склад\")\n#КонецЕсли");
    assertThat(read.get(1).get("fields")).isEqualTo(List.of("Цена"));
    assertThat(read.get(1).get("condition")).isEqualTo("ГДЕ ЛОЖЬ");

    // Блок Catalog.ТоварыПоставщиков начинается с того же имени, но подчинённым не считается
    assertThat(storekeeper.children).extracting(child -> child.name).containsExactly("Attribute.Цена");
    assertThat(storekeeper.children.get(0).rights).containsExactly(Map.entry("View", false), Map.entry("Edit", false));
  }

  @Test
  void readsRestrictionsOfRightThatIsNotGranted() throws Exception {
    ObjectRights.RoleDto storekeeper = role(ObjectRights.read(FIXTURE.resolve("cf/Documents/Заказ.xml")), "Кладовщик");

    assertThat(storekeeper.granted).containsExactly("Read");
    assertThat(storekeeper.restrictions).containsOnlyKeys("Insert");
    assertThat(storekeeper.restrictions.get("Insert").get(0).get("condition")).isEqualTo("ГДЕ ЛОЖЬ");
  }

  @Test
  void readsConfigurationRights() throws Exception {
    ObjectRights.Dto dto = ObjectRights.read(FIXTURE.resolve("cf/Configuration.xml"));

    assertThat(dto.object).isEqualTo("Configuration.Склад");
    assertThat(dto.kind).isEqualTo("Configuration");
    assertThat(role(dto, "Администратор").granted).containsExactlyElementsOf(dto.rights);
    assertThat(role(dto, "Гость").granted).isEmpty();
    assertThat(role(dto, "Кладовщик").granted).containsExactly("ThinClient");
  }

  @Test
  void readsEdtProjectLikeDesignerDump() throws Exception {
    ObjectRights.Dto designer = ObjectRights.read(FIXTURE.resolve("cf/Catalogs/Товары.xml"));
    ObjectRights.Dto edt = ObjectRights.read(FIXTURE.resolve("edt/src/Catalogs/Товары/Товары.mdo"));

    assertThat(edt.object).isEqualTo(designer.object);
    assertThat(edt.rights).isEqualTo(designer.rights);
    assertThat(new Gson().toJson(edt.roles)).isEqualTo(new Gson().toJson(designer.roles));
    assertThat(edt.editable).isFalse();
    assertThat(edt.readonlyReason).isNotBlank();
  }

  private static ObjectRights.RoleDto role(ObjectRights.Dto dto, String name) {
    return dto.roles.stream().filter(role -> role.name.equals(name)).findFirst().orElseThrow();
  }
}
