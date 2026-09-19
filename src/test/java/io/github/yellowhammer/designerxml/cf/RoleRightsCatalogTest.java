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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class RoleRightsCatalogTest {

  @Test
  void everyRightOfEveryKindHasLabel() {
    Map<String, String> labels = UiLabels.rights();
    for (Map.Entry<String, List<String>> kind : RoleRightsCatalog.rightsByKind().entrySet()) {
      assertThat(kind.getValue()).as(kind.getKey()).isNotEmpty();
      for (String right : kind.getValue()) {
        assertThat(labels).as(kind.getKey() + "." + right).containsKey(right);
      }
    }
  }

  @Test
  void requiredRightsBelongToTheSameKind() {
    for (String name : RoleRightsCatalog.rightsByKind().keySet()) {
      RoleRightsCatalog.Kind kind = RoleRightsCatalog.kind(name);
      kind.requires().forEach((right, required) -> {
        assertThat(kind.rights()).as(name).contains(right);
        assertThat(kind.rights()).as(name + "." + right).containsAll(required);
      });
    }
  }

  @Test
  void kindsWithoutRightsAreUnknown() {
    assertThat(RoleRightsCatalog.kind("Enum")).isNull();
    assertThat(RoleRightsCatalog.kind("CommonModule")).isNull();
  }
}
