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
package io.github.yellowhammer.designerxml.cli;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.yellowhammer.designerxml.Ssl31SubmodulePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code serve} отдельным процессом: stdout занят только протоколом. */
class ServeProcessTest {

  @TempDir
  Path tempDir;

  @Test
  void процессОтвечаетКакРазовыйЗапускИВыходитПоКонцуВвода() throws Exception {
    Path catalog = Ssl31SubmodulePaths.anyCatalogObjectXml();
    Path params = tempDir.resolve("params.json");
    Files.writeString(params, "{\"op\":\"cf-md-object-get\",\"objectXml\":" + new JsonPrimitive(catalog.toString())
      + ",\"schemaVersion\":\"V2_20\"}", StandardCharsets.UTF_8);

    // Кодировку вывода процессу не задаём: протокол от неё не зависит
    Process serve = java(List.of(), "serve").start();
    CompletableFuture<byte[]> errors = CompletableFuture.supplyAsync(() -> readAll(serve));
    JsonObject ready;
    JsonObject byParams;
    JsonObject byArgs;
    boolean exited;
    List<String> rest = new ArrayList<>();
    try (BufferedReader responses = new BufferedReader(
        new InputStreamReader(serve.getInputStream(), StandardCharsets.UTF_8));
      OutputStream requests = serve.getOutputStream()) {
      ready = JsonParser.parseString(responses.readLine()).getAsJsonObject();
      write(requests, ServeSessionTest.requestLine(1, null, "read-json", "--params", params.toString()));
      byParams = JsonParser.parseString(responses.readLine()).getAsJsonObject();
      write(requests, ServeSessionTest.requestLine(2, null, "cf-md-object-get", catalog.toString(), "-v", "V2_20"));
      byArgs = JsonParser.parseString(responses.readLine()).getAsJsonObject();
      requests.close();
      exited = serve.waitFor(60, TimeUnit.SECONDS);
      for (String line = responses.readLine(); line != null; line = responses.readLine()) {
        rest.add(line);
      }
    }

    Process oneShot = java(
      List.of("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"),
      "read-json", "--params", params.toString()).start();
    CompletableFuture<byte[]> oneShotErrors = CompletableFuture.supplyAsync(() -> readAll(oneShot));
    String oneShotStdout = new String(oneShot.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(oneShot.waitFor(60, TimeUnit.SECONDS)).isTrue();
    oneShotErrors.get(10, TimeUnit.SECONDS);

    assertThat(ready.get("ready").getAsBoolean()).isTrue();
    assertThat(byParams.get("id").getAsLong()).isEqualTo(1);
    assertThat(byParams.get("exitCode").getAsInt()).isEqualTo(oneShot.exitValue()).isZero();
    assertThat(byParams.get("stdout").getAsString()).contains("_Демо");
    assertThat(withoutXmlFragments(byParams.get("stdout").getAsString())).isEqualTo(withoutXmlFragments(oneShotStdout));
    assertThat(byArgs.get("exitCode").getAsInt()).isZero();
    assertThat(byArgs.get("stdout").getAsString()).contains("_Демо");
    assertThat(exited).isTrue();
    assertThat(serve.exitValue()).isZero();
    assertThat(rest).isEmpty();
    assertThat(new String(errors.get(10, TimeUnit.SECONDS), StandardCharsets.UTF_8)).isEmpty();
  }

  /** Ответ без XML-фрагментов: раскладка префиксов пространств имён в них у каждого процесса своя. */
  private static JsonElement withoutXmlFragments(String stdout) {
    JsonElement tree = JsonParser.parseString(stdout);
    dropXmlFragments(tree);
    return tree;
  }

  private static void dropXmlFragments(JsonElement node) {
    if (node.isJsonObject()) {
      JsonObject object = node.getAsJsonObject();
      object.keySet().removeIf(key -> key.endsWith("Xml"));
      object.entrySet().forEach(entry -> dropXmlFragments(entry.getValue()));
    } else if (node.isJsonArray()) {
      node.getAsJsonArray().forEach(ServeProcessTest::dropXmlFragments);
    }
  }

  private static ProcessBuilder java(List<String> options, String... args) {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(options);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(DesignerXmlCli.class.getName());
    command.addAll(List.of(args));
    return new ProcessBuilder(command);
  }

  private static void write(OutputStream requests, String line) throws IOException {
    requests.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    requests.flush();
  }

  private static byte[] readAll(Process process) {
    try {
      return process.getErrorStream().readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
