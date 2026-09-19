/*
 * This file is a part of md-sparrow.
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */
package io.github.yellowhammer.designerxml.cf;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yellowhammer.designerxml.SchemaVersion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

/**
 * Каналы сервиса интеграции в выгрузке конфигуратора.
 *
 * <p>Фикстура {@code cf-integration-service} выгружена платформой 8.3.27: сервис
 * с двумя каналами, у отправки есть синоним, у получения обработчик в модуле.
 */
class IntegrationServiceChannelsTest {

  private static final Path SERVICE = Path.of(
    "src", "test", "resources", "cf-integration-service", "IntegrationServices", "СервисИнтеграции1.xml")
    .toAbsolutePath();

  @BeforeEach
  void forgetLanguages() {
    ConfigurationLanguage.forget();
  }

  @Test
  void каналыВходятВСтроение() throws Exception {
    MdObjectStructureDto dto = MdObjectStructureRead.read(SERVICE, SchemaVersion.V2_20);

    assertThat(dto.kind).isEqualTo("integrationService");
    assertThat(dto.channels).containsExactly("Отправка", "Получение");
  }

  @Test
  void каналыЧитаютсяСоСвойствами() throws Exception {
    MdObjectPropertiesDto dto = MdObjectPropertiesEdit.readDto(SERVICE, SchemaVersion.V2_20);

    assertThat(dto.channels).extracting(node -> node.name).containsExactly("Отправка", "Получение");
    assertThat(dto.channels).extracting(node -> node.synonym).containsExactly("Отправка заказов", "");
  }
}
