/*
 * This file is a part of md-sparrow.
 *
 * Copyright (c) 2026
 * Ivan Karlo <i.karlo@outlook.com> and contributors
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * md-sparrow is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * md-sparrow is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with md-sparrow.
 */
package io.github.yellowhammer.edt;

import java.util.Map;

/**
 * Списки узлов, которые контракт зовёт иначе, чем схема 1С:EDT.
 *
 * Остальные узлы названы в контракте так же, как свойства схемы, и находятся по
 * имени поля.
 */
final class EdtContractNames {

  /** Свойство схемы по полю контракта. */
  private static final Map<String, String> FEATURE_BY_FIELD = Map.of("channels", "integrationServiceChannels");

  private EdtContractNames() {
  }

  /**
   * Свойство схемы, в котором лежат узлы поля контракта.
   *
   * @param field поле контракта: {@code attributes}, {@code channels}
   * @return свойство схемы: {@code attributes}, {@code integrationServiceChannels}
   */
  static String feature(String field) {
    return FEATURE_BY_FIELD.getOrDefault(field, field);
  }

  /**
   * Поле контракта, в которое читаются узлы свойства схемы.
   *
   * @param feature свойство схемы: {@code attributes}, {@code integrationServiceChannels}
   * @return поле контракта: {@code attributes}, {@code channels}
   */
  static String field(String feature) {
    for (Map.Entry<String, String> entry : FEATURE_BY_FIELD.entrySet()) {
      if (entry.getValue().equals(feature)) {
        return entry.getKey();
      }
    }
    return feature;
  }
}
