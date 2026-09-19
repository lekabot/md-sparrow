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

import picocli.CommandLine.Command;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.concurrent.Callable;

/**
 * Резидентный режим: команды по запросам из stdin, ответы в stdout.
 *
 * <p>Протокол описан в {@link ServeSession}.
 */
@Command(
  name = "serve",
  description = "Выполнять команды по запросам из stdin: запрос и ответ - строка JSON в UTF-8."
)
final class ServeCmd implements Callable<Integer> {

  @Override
  public Integer call() {
    if (ServeSession.active()) {
      System.err.println("Режим serve уже запущен в этом процессе.");
      return 2;
    }
    InputStream requests = System.in;
    PrintStream diagnostics = System.err;
    FileOutputStream responses = new FileOutputStream(FileDescriptor.out);
    // stdout занят ответами: печать вне запроса уходит в stderr, а ввод команд пуст
    System.setOut(diagnostics);
    System.setIn(InputStream.nullInputStream());
    return new ServeSession(requests, responses, diagnostics).run();
  }
}
