/*
 * The MIT License
 *
 * Copyright (c) Red Hat, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.redhat.jenkins.nodesharing;

import hudson.FilePath;
import hudson.Platform;
import hudson.console.AnnotatedLargeText;
import hudson.util.StreamTaskListener;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;

/**
 * Log progress of a task to file while marking error was reported.
 *
 * @author ogondza.
 */
public class TaskLog extends StreamTaskListener implements AutoCloseable, Closeable {
    private static final long serialVersionUID = 1576021666075069316L;

    private final File target;
    private boolean failed = false;
    private volatile boolean completed = false;

    public TaskLog(File out) throws IOException {
        super(out, Charset.defaultCharset());
        target = out;
    }

    public boolean failed() {
        return failed;
    }

    public void throwIfFailed(@Nonnull String msg) throws TaskFailed {
        if (failed) throw new TaskFailed(this, msg);
    }

    public String readContent() throws IOException, InterruptedException {
        return new FilePath(target).readToString();
    }

    public AnnotatedLargeText<TaskLog> getAnnotatedText() {
        return new AnnotatedLargeText<TaskLog>(target, Charset.defaultCharset(), completed, this);
    }

    public void println(String msg) {
        getLogger().printf(msg);
    }

    public void printf(String format, Object... args) {
        getLogger().printf(format, args);
    }

    @Override public PrintWriter error(String msg) {
        failed = true;
        return super.error(msg);
    }

    @Override public PrintWriter error(String format, Object... args) {
        failed = true;
        return super.error(format, args);
    }

    public PrintWriter error(Throwable ex, String format, Object... args) {
        failed = true;
        PrintWriter printWriter = super.error(format, args);
        ex.printStackTrace(printWriter);
        return printWriter;
    }

    @Override public PrintWriter fatalError(String msg) {
        failed = true;
        return super.fatalError(msg);
    }

    @Override public PrintWriter fatalError(String format, Object... args) {
        failed = true;
        return super.fatalError(format, args);
    }

    @Override public void close() throws IOException {
        this.completed = true;
        super.close();
    }

    /**
     * The task has reported at least one error.
     */
    public static final class TaskFailed extends RuntimeException {
        private static final long serialVersionUID = -5178584756306856550L;

        private final @Nonnull TaskLog log;

        public TaskFailed(@Nonnull TaskLog log, @Nonnull String message) {
            super(message);
            this.log = log;
        }

        /**
         * The task log that has reported at least one error.
         */
        public @Nonnull TaskLog getLog() {
            return log;
        }

        // The log can contain a lot of lines that are inconvenient to put into message yet interesting enough to have
        // it reported somewhere. Overriding toString to contain the full log as that is what printStackTrace overloads
        // delegate to.
        @Override
        public String toString() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintWriter ps = new PrintWriter(baos);
            ps.println(super.toString());
            try {
                ps.println(">>>>>>");
                try {
                    log.getAnnotatedText().writeLogTo(0, ps);
                } catch (IOException e) {
                    ps.println("(FAILED READING THE TASK LOG):");
                    e.printStackTrace(ps);
                }
                ps.println("<<<<<<");
            } finally {
                ps.close();
            }

            return baos.toString();
        }
    }
}
