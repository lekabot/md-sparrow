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
package io.github.yellowhammer.designerxml;

import java.util.concurrent.Callable;

/**
 * Отмена операции по просьбе вызывающей программы.
 *
 * <p>Операция прерывается только в точках отмены {@link #checkpoint()}. Они стоят
 * в читающих циклах между файлами: запись до конца доводится всегда.
 */
public final class Cancellation {

  private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();

  private Cancellation() {
  }

  /** Признак отмены одной операции. */
  public static final class Token {

    private volatile boolean requested;
    private volatile boolean stopped;

    /** Создаёт признак: операция не отменена. */
    public Token() {
    }

    /** Просит прервать операцию на ближайшей точке отмены. */
    public void cancel() {
      requested = true;
    }

    /**
     * Операция прервалась на точке отмены и не дошла до конца.
     *
     * @return {@code true}, если сработала хотя бы одна точка отмены
     */
    public boolean stopped() {
      return stopped;
    }
  }

  /** Операция прервана по отмене. */
  public static final class CancelledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Создаёт исключение с текстом об отмене. */
    public CancelledException() {
      super("Операция отменена.");
    }
  }

  /**
   * Выполняет работу, точки отмены которой смотрят на {@code token}.
   *
   * @param token признак отмены
   * @param work работа
   * @param <T> её результат
   * @return результат работы
   * @throws Exception исключение работы
   */
  public static <T> T run(Token token, Callable<T> work) throws Exception {
    Token previous = CURRENT.get();
    CURRENT.set(token);
    try {
      return work.call();
    } finally {
      if (previous == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(previous);
      }
    }
  }

  /**
   * Точка отмены: бросает {@link CancelledException}, если операцию просили прервать.
   *
   * <p>Вне {@link #run} ничего не делает.
   */
  public static void checkpoint() {
    Token token = CURRENT.get();
    if (token != null && token.requested) {
      token.stopped = true;
      throw new CancelledException();
    }
  }
}
