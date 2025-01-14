/*
 * Copyright 2022 VMware, Inc.
 * SPDX-License-Identifier: MIT
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package net.hydromatic.sqllogictest.executors;

import net.hydromatic.sqllogictest.ExecutionOptions;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * A test executor that uses HSQLDB through JDBC.
 */
public class HsqldbExecutor extends JdbcExecutor {
  /**
   * Unique connection id for HSQLDB (allocate a different database for every
   * connection).
   */
  private static final AtomicLong HSQLDB_CONNECTION_ID = new AtomicLong(0);

  /**
   * Register the HSQL DB executor with the command-line options.
   * @param options  Options that will guide the execution.
   */
  public static void register(ExecutionOptions options) {
    options.registerExecutor("hsql", () -> {
      HsqldbExecutor result = new HsqldbExecutor(options);
      try {
        Set<String> bugs = options.readBugsFile();
        result.avoid(bugs);
        return result;
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  /**
   * Create a test executor that uses HSQL DB to execute tests.
   * @param options Options guiding the test execution.
   */
  public HsqldbExecutor(ExecutionOptions options) {
    super(options,
        "jdbc:hsqldb:mem:db" + HSQLDB_CONNECTION_ID.getAndIncrement(), "", "");
  }

  @Override List<String> getTableList() throws SQLException {
    List<String> result = super.getTableList();
    // Remove the "PUBLIC" table, which is the HSQLDB catalog
    result = result.stream()
            .filter(e -> !e.equals("PUBLIC"))
            .collect(Collectors.toList());
    return result;
  }

  @Override public void establishConnection() throws SQLException {
    super.establishConnection();
    assert this.connection != null;
    try (Statement statement = this.connection.createStatement()) {
      // Enable postgres compatibility
      statement.execute("SET DATABASE SQL SYNTAX PGS TRUE");
    }
  }
}
