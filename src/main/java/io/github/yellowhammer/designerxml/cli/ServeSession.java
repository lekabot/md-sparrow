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
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import io.github.yellowhammer.designerxml.Cancellation;
import io.github.yellowhammer.designerxml.cf.ConfigurationLanguage;
import io.github.yellowhammer.designerxml.cf.SupportRules;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сеанс {@code serve}: команды по запросам из входного потока, ответы строками JSON.
 *
 * <p>Запрос {@code {"id":1,"args":[...],"cwd":"..."}} выполняется так же, как разовый запуск
 * с этими аргументами в каталоге {@code cwd}; ответ {@code {"id":1,"exitCode":0,"stdout":"...","stderr":"..."}}.
 * Отмена {@code {"id":1,"cancel":true}}, завершение {@code {"shutdown":true}} или конец ввода.
 *
 * <p>Запросы выполняются по одному в порядке поступления. Ввод читает отдельный поток: так
 * отмена доходит, пока запрос выполняется.
 */
final class ServeSession {

  /** Версия протокола в строке готовности. */
  static final int PROTOCOL = 1;

  /** Код ответа на отменённый запрос. */
  static final int EXIT_CANCELLED = 130;

  private static final int EXIT_FAILURE = 1;
  private static final int EXIT_INVALID = 2;

  private static final String CANCELLED = "Запрос отменён.";

  /** Сеанс уже идёт: вложенный забрал бы ввод и вывод у текущего. */
  private static final AtomicBoolean ACTIVE = new AtomicBoolean();

  /** Метка конца очереди. */
  private static final Request STOP = new Request(0, new String[0], null);

  private final BufferedReader input;
  private final Writer output;
  private final PrintStream log;

  private final BlockingQueue<Request> queue = new LinkedBlockingQueue<>();

  /** Принятые запросы без ответа, по id. */
  private final Map<Long, Request> accepted = new HashMap<>();

  /** Новые запросы не принимаются. */
  private boolean closing;

  private volatile boolean outputFailed;

  /**
   * @param input запросы, UTF-8
   * @param output ответы, UTF-8
   * @param log диагностика сеанса
   */
  ServeSession(InputStream input, OutputStream output, PrintStream log) {
    this.input = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    this.output = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
    this.log = log;
  }

  /** Сеанс идёт в этом процессе. */
  static boolean active() {
    return ACTIVE.get();
  }

  /**
   * Печатает строку готовности и выполняет запросы до завершения.
   *
   * @return код выхода процесса
   */
  int run() {
    if (!ACTIVE.compareAndSet(false, true)) {
      throw new IllegalStateException("Сеанс serve уже идёт в этом процессе.");
    }
    try {
      writeReady();
      Thread reader = new Thread(this::readRequests, "md-sparrow serve input");
      reader.setDaemon(true);
      reader.start();
      return executeRequests();
    } finally {
      ACTIVE.set(false);
    }
  }

  private int executeRequests() {
    while (!outputFailed) {
      Request request;
      try {
        request = queue.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return EXIT_FAILURE;
      }
      if (request == STOP) {
        return outputFailed ? EXIT_FAILURE : 0;
      }
      if (!start(request)) {
        continue;
      }
      Result result;
      try {
        result = execute(request);
      } catch (OutOfMemoryError e) {
        result = new Result(EXIT_FAILURE, "", "Не хватило памяти, процесс завершается.", true);
      }
      finish(request);
      respond(new JsonPrimitive(request.id), result.exitCode, result.stdout, result.stderr, result.fatal);
      if (result.fatal) {
        log.println("md-sparrow serve: процесс завершается после сбоя виртуальной машины");
        return EXIT_FAILURE;
      }
    }
    return EXIT_FAILURE;
  }

  /** Выполняет запрос с перехватом его stdout и stderr. */
  private static Result execute(Request request) {
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
    PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8);
    PrintWriter cliOut = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
    PrintWriter cliErr = new PrintWriter(new OutputStreamWriter(err, StandardCharsets.UTF_8), true);
    PrintStream savedOut = System.out;
    PrintStream savedErr = System.err;
    System.setOut(out);
    System.setErr(err);
    int exitCode;
    boolean fatal = false;
    try {
      resetProcessState();
      CommandLine cli = DesignerXmlCli.commandLine(request.cwd);
      cli.setOut(cliOut);
      cli.setErr(cliErr);
      exitCode = Cancellation.run(request.token, () -> cli.execute(request.args));
    } catch (Throwable e) {
      // У разового запуска исключение мимо picocli завершает процесс с кодом 1
      exitCode = EXIT_FAILURE;
      fatal = e instanceof VirtualMachineError && !(e instanceof StackOverflowError);
      e.printStackTrace(err);
    } finally {
      cliOut.flush();
      cliErr.flush();
      System.setOut(savedOut);
      System.setErr(savedErr);
    }
    if (request.token.stopped()) {
      return new Result(EXIT_CANCELLED, "", CANCELLED, fatal);
    }
    return new Result(
      exitCode,
      stdout.toString(StandardCharsets.UTF_8),
      stderr.toString(StandardCharsets.UTF_8),
      fatal);
  }

  /** Состояние, которое у разового запуска живёт один процесс: каждый запрос начинает с чистого. */
  private static void resetProcessState() {
    SupportRules.setEnforced(true);
    SupportRules.forget();
    ConfigurationLanguage.forget();
  }

  private void readRequests() {
    try {
      String line;
      while ((line = input.readLine()) != null) {
        try {
          accept(line);
        } catch (RuntimeException e) {
          respond(JsonNull.INSTANCE, EXIT_INVALID, "", "Запрос не принят: " + e);
        }
      }
    } catch (IOException e) {
      log.println("md-sparrow serve: ввод прочитать не удалось: " + e.getMessage());
    } finally {
      close();
    }
  }

  private void accept(String line) {
    if (line.isBlank()) {
      return;
    }
    JsonObject message;
    try {
      message = parseObject(line);
    } catch (JsonParseException e) {
      respond(JsonNull.INSTANCE, EXIT_INVALID, "", "Строка не разобрана как объект JSON: " + e.getMessage());
      return;
    }
    if (isTrue(message.get("shutdown"))) {
      close();
      return;
    }
    JsonElement idElement = message.get("id");
    JsonElement echo = idElement != null && idElement.isJsonPrimitive() ? idElement : JsonNull.INSTANCE;
    Long id = idOf(idElement);
    if (id == null) {
      respond(echo, EXIT_INVALID, "", "В запросе нет целочисленного id.");
      return;
    }
    if (isTrue(message.get("cancel"))) {
      cancel(id);
      return;
    }
    String[] args = argsOf(message.get("args"));
    if (args == null) {
      respond(echo, EXIT_INVALID, "", "Поле args должно быть массивом строк.");
      return;
    }
    Path cwd;
    try {
      cwd = cwdOf(message.get("cwd"));
    } catch (IllegalArgumentException e) {
      respond(echo, EXIT_INVALID, "", e.getMessage());
      return;
    }
    String refusal = enqueue(new Request(id, args, cwd));
    if (refusal != null) {
      respond(echo, EXIT_INVALID, "", refusal);
    }
  }

  /** @return причина отказа либо {@code null}, если запрос принят */
  private synchronized String enqueue(Request request) {
    if (closing) {
      return "Сеанс завершается, запрос не принят.";
    }
    if (accepted.containsKey(request.id)) {
      return "Запрос с id " + request.id + " уже принят.";
    }
    accepted.put(request.id, request);
    queue.add(request);
    return null;
  }

  private void cancel(long id) {
    synchronized (this) {
      Request request = accepted.get(id);
      if (request == null) {
        return;
      }
      if (request.started) {
        request.token.cancel();
        return;
      }
      accepted.remove(id);
      request.dropped = true;
      queue.remove(request);
    }
    respond(new JsonPrimitive(id), EXIT_CANCELLED, "", CANCELLED);
  }

  private synchronized boolean start(Request request) {
    if (request.dropped) {
      return false;
    }
    request.started = true;
    return true;
  }

  private synchronized void finish(Request request) {
    accepted.remove(request.id);
  }

  /** Больше не принимать запросы; принятые выполняются до конца. */
  private void close() {
    synchronized (this) {
      if (closing) {
        return;
      }
      closing = true;
    }
    queue.add(STOP);
  }

  private void writeReady() {
    writeLine(json -> {
      json.beginObject();
      json.name("ready").value(true);
      json.name("version").value(DesignerXmlCli.version());
      json.name("protocol").value(PROTOCOL);
      json.endObject();
    });
  }

  private void respond(JsonElement id, int exitCode, String stdout, String stderr) {
    respond(id, exitCode, stdout, stderr, false);
  }

  /**
   * @param closing последний ответ сеанса: следующих запросов процесс не выполнит
   */
  private void respond(JsonElement id, int exitCode, String stdout, String stderr, boolean closing) {
    writeLine(json -> {
      json.beginObject();
      json.name("id");
      if (id.isJsonPrimitive()) {
        JsonPrimitive value = id.getAsJsonPrimitive();
        if (value.isNumber()) {
          json.value(value.getAsNumber());
        } else if (value.isBoolean()) {
          json.value(value.getAsBoolean());
        } else {
          json.value(value.getAsString());
        }
      } else {
        json.nullValue();
      }
      json.name("exitCode").value(exitCode);
      json.name("stdout").value(stdout);
      json.name("stderr").value(stderr);
      if (closing) {
        json.name("closing").value(true);
      }
      json.endObject();
    });
  }

  /** Сообщение протокола. */
  private interface Message {
    void write(JsonWriter json) throws IOException;
  }

  /** Пишет сообщение одной строкой: ответы приходят из двух потоков. */
  private void writeLine(Message message) {
    synchronized (output) {
      if (outputFailed) {
        return;
      }
      try {
        JsonWriter json = new JsonWriter(output);
        json.setHtmlSafe(false);
        json.setSerializeNulls(true);
        message.write(json);
        json.flush();
        output.write('\n');
        output.flush();
      } catch (IOException e) {
        outputFailed = true;
        log.println("md-sparrow serve: ответ записать не удалось: " + e.getMessage());
        queue.add(STOP);
      }
    }
  }

  private static JsonObject parseObject(String line) {
    JsonReader reader = new JsonReader(new StringReader(line));
    reader.setStrictness(Strictness.STRICT);
    JsonElement element = JsonParser.parseReader(reader);
    try {
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw new JsonSyntaxException("после объекта есть лишние символы");
      }
    } catch (IOException e) {
      throw new JsonSyntaxException(e);
    }
    if (!element.isJsonObject()) {
      throw new JsonSyntaxException("ожидался объект");
    }
    return element.getAsJsonObject();
  }

  private static boolean isTrue(JsonElement element) {
    return element != null
      && element.isJsonPrimitive()
      && element.getAsJsonPrimitive().isBoolean()
      && element.getAsBoolean();
  }

  private static Long idOf(JsonElement element) {
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
      return null;
    }
    try {
      return new BigDecimal(element.getAsString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException e) {
      return null;
    }
  }

  private static String[] argsOf(JsonElement element) {
    if (element == null || !element.isJsonArray()) {
      return null;
    }
    List<String> args = new ArrayList<>();
    for (JsonElement item : element.getAsJsonArray()) {
      if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
        return null;
      }
      args.add(item.getAsString());
    }
    return args.toArray(String[]::new);
  }

  private static Path cwdOf(JsonElement element) {
    if (element == null || element.isJsonNull()) {
      return null;
    }
    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
      throw new IllegalArgumentException("Поле cwd должно быть строкой.");
    }
    String value = element.getAsString();
    Path path;
    try {
      path = Path.of(value);
    } catch (InvalidPathException e) {
      throw new IllegalArgumentException("Каталог cwd задан неверно: " + value);
    }
    if (!path.isAbsolute() || !Files.isDirectory(path)) {
      throw new IllegalArgumentException("Каталог cwd должен быть абсолютным путём к существующему каталогу: " + value);
    }
    return path;
  }

  /** Принятый запрос. */
  private static final class Request {
    final long id;
    final String[] args;
    final Path cwd;
    final Cancellation.Token token = new Cancellation.Token();

    /** Выполнение началось: отмена идёт через точки отмены. Под блокировкой сеанса. */
    boolean started;

    /** Отменён до начала выполнения. Под блокировкой сеанса. */
    boolean dropped;

    Request(long id, String[] args, Path cwd) {
      this.id = id;
      this.args = args;
      this.cwd = cwd;
    }
  }

  private record Result(int exitCode, String stdout, String stderr, boolean fatal) {
  }
}
