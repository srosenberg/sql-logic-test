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
package net.hydromatic.sqllogictest;

import net.hydromatic.sqllogictest.util.Utilities;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the data from a .test file from the
 * SqlLogicTest test framework.
 */
/*
 *         The Test file format is described at
 *         https://www.sqlite.org/sqllogictest/doc/tip/about.wiki.
 *
 *         Here is an example:
 *
 *         hash-threshold 8
 *
 *         statement ok
 *         CREATE TABLE t1(a INTEGER, b INTEGER, c INTEGER, d INTEGER, e INTEGER)
 *
 *         statement ok
 *         INSERT INTO t1(e,c,b,d,a) VALUES(NULL,102,NULL,101,104)
 *
 *         statement ok
 *         INSERT INTO t1(a,c,d,e,b) VALUES(107,106,108,109,105)
 *
 *         query I nosort
 *         SELECT CASE WHEN c>(SELECT avg(c) FROM t1) THEN a*2 ELSE b*10 END
 *           FROM t1
 *          ORDER BY 1
 *         ----
 *         30 values hashing to 3c13dee48d9356ae19af2515e05e6b54
 *
 */
public class SltTestFile {
  /**
   * This policy accepts all SLT queries and statements written in the Postgres
   * SQL language.
   */
  static class PostgresPolicy {
    /**
     * Function that decides whether a statement or query is executed at all.
     * @param skip   List of conditions under which the stat/query is skipped.
     * @param only   List of conditions under which the stat/query is executed.
     * @return       True if the stat/query is executed.
     */
    public boolean accept(List<String> skip, List<String> only) {
      if (only.contains("postgresql") || only.contains("cockroachdb")) {
        return true;
      }
      if (!only.isEmpty()) {
        return false;
      }
      return !skip.contains("postgresql") && !skip.contains("cockroachdb");
    }
  }

  /**
   * Current line number in test file.
   */
  private int lineNo;
  /**
   * A file is parsed into a list of operations: statements or queries.
   */
  public final List<ISqlTestOperation> fileContents;
  private final BufferedReader reader;
  // To support undo for reading
  private @Nullable String nextLine;
  private final String testFile;
  private boolean done;
  private int testCount;

  /**
   * Create a test file from the file with the specified name.
   */
  public SltTestFile(String testFile) throws IOException {
    File file = new File(testFile);
    this.reader = new BufferedReader(new FileReader(file));
    this.fileContents = new ArrayList<>();
    this.lineNo = 0;
    this.testFile = testFile;
    this.done = false;
    this.testCount = 0;
  }

  void error(String message) {
    throw new RuntimeException("File " + this.testFile
        + "\nError at line " + this.lineNo + ": " + message);
  }

  private void undoRead(String line) {
    if (this.nextLine != null) {
      throw new RuntimeException("Only one undoRead allowed");
    }
    this.nextLine = line;
  }

  String getNextLine(boolean nullOk) throws IOException {
    String line;
    if (this.nextLine != null) {
      line = this.nextLine;
      this.nextLine = null;
    } else {
      this.lineNo++;
      line = this.reader.readLine();
      if (!nullOk && line == null) {
        this.error("Test file ends prematurely");
      }
      if (line == null) {
        this.done = true;
        line = "";
      }
    }
    return line.trim();
  }

  String nextLine(boolean nullOk) throws IOException {
    while (true) {
      String line = this.getNextLine(nullOk);
      if (this.done) {
        return line;
      }
      // Drop comments
      int sharp = line.indexOf("#");
      if (sharp > 0) {
        return line.substring(0, sharp - 1);
      } else if (sharp < 0) {
        return line;
      }
      // else read one more
    }
  }

  /**
   * Parse a query that executes a SqlLogicTest test.
   */
  private @Nullable SqlTestQuery parseTestQuery() throws IOException {
    @Nullable String line = this.nextLine(true);
    if (this.done) {
      return null;
    }
    assert line != null;  // otherwise 'done' should be set
    while (line.isEmpty()) {
      line = this.nextLine(false);
    }

    if (!line.startsWith("query")) {
      this.error("Unexpected line: " + Utilities.singleQuote(line));
    }
    @Nullable SqlTestQuery result = new SqlTestQuery(this.testFile);
    line = line.substring("query" .length()).trim();
    if (line.isEmpty()) {
      this.error("Malformed query description " + line);
    }

    line = result.outputDescription.parseType(line);
    if (line == null) {
      this.error("Could not parse output column types");
    }
    assert line != null;
    line = line.trim();
    if (line.isEmpty()) {
      this.error("Malformed query description " + line);
    }

    line = result.outputDescription.parseOrder(line);
    if (line == null) {
      this.error("Did not understand sort order");
    }
    assert line != null;
    line = line.trim();
    if (!line.isEmpty()) {
      result.setName(line);
    }

    line = this.nextLine(false);
    StringBuilder query = new StringBuilder();
    if (!this.done) {
      while (!this.done && !line.startsWith("----")) {
        query.append(" ");
        query.append(line);
        line = this.nextLine(true);
      }
    }

    String q = query.toString().trim();
    result.setQuery(q, this.lineNo);

    final String vht = "values hashing to";
    if (!this.done) {
      line = this.nextLine(true);
      if (!this.done) {
        if (line.contains(vht)) {
          int vi = line.indexOf(vht);
          String number = line.substring(0, vi - 1);
          int values = Integer.parseInt(number);
          result.outputDescription.setValueCount(values);
          line = line.substring(vi + vht.length()).trim();
          result.outputDescription.setHash(line);
          line = this.nextLine(true);
          if (!this.done && !line.isEmpty()) {
            this.error("Expected an empty line between tests: "
                + Utilities.singleQuote(line));
          }
        } else {
          result.outputDescription.clearResults();
          while (!line.isEmpty()) {
            result.outputDescription.addResultLine(line);
            line = this.getNextLine(true);
          }
        }
      } else {
        result.outputDescription.clearResults();
      }
    }
    return result;
  }

  /**
   * Parse the contents of the file.
   * @param options      Options which guide the parsing.
   */
  public void parse(ExecutionOptions options) throws IOException {
    PostgresPolicy policy = new PostgresPolicy();

    String line;
    while (!this.done) {
      line = this.nextLine(true);
      if (this.done) {
        return;
      }
      if (line.isEmpty()) {
        continue;
      }
      if (line.startsWith("hash-threshold")) {
        continue;
      }

      List<String> skip = new ArrayList<>();
      List<String> only = new ArrayList<>();
      while (line.startsWith("onlyif") || line.startsWith("skipif")) {
        boolean sk = line.startsWith("skipif");
        String cond = line.substring("onlyif" .length()).trim();
        if (sk) {
          skip.add(cond);
        } else {
          only.add(cond);
        }
        line = this.nextLine(false);
      }

      if (line.startsWith("halt")) {
        if (policy.accept(skip, only)) {
          break;
        }
        continue;
      }

      if (line.startsWith("statement")) {
        boolean ok = line.startsWith("statement ok");
        line = this.nextLine(false);
        StringBuilder statement = new StringBuilder();
        while (!this.done && !line.isEmpty()) {
          statement.append(line);
          line = this.nextLine(true);
        }
        String command = statement.toString();
        SltSqlStatement stat = new SltSqlStatement(command, ok);
        if (policy.accept(skip, only)) {
          this.add(stat, options);
        }
      } else {
        this.undoRead(line);
        SqlTestQuery test = this.parseTestQuery();
        if (test != null && policy.accept(skip, only)) {
          this.add(test, options);
        }
      }
    }
  }

  private void add(ISqlTestOperation operation, ExecutionOptions options) {
    options.message("Operation added " + operation.toString(), 2);
    this.fileContents.add(operation);
    if (operation.is(SqlTestQuery.class)) {
      this.testCount++;
    }
  }

  /**
   * Number of tests in the file.
   */
  public int getTestCount() {
    return this.testCount;
  }

  @Override public String toString() {
    return this.testFile;
  }
}
