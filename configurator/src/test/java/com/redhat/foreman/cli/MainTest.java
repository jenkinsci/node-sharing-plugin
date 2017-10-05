package com.redhat.foreman.cli;

import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

@RunWith(Theories.class)
public class MainTest {

    @DataPoints
    public static final Invoker[] INVOKERS = new Invoker[] {
            new MainInvoker(), // Invoke main class programmatically
            new WrapperInvoker() // Invoke the tool through shell wrapper
    };

    @Theory
    public void testHelp(Invoker inv) {
        assertEquals(2, inv.run("--help"));
        assertThat(inv.out(), containsString("Usage: "));
        assertThat(inv.out(),
                containsString("Usage: create [options] <Files to process>"));
        assertThat(inv.out(),
                containsString("Usage: release [options] <the list of hosts to release>"));
        //Usage: list [options]
        assertThat(inv.out(), containsString("Usage: list [options]"));
        assertThat(inv.out(), containsString("Usage: update [options] <Files to process>"));
    }

    @Theory
    public void testUnknownCommand(Invoker inv) {
        assertEquals(3, inv.run("dummyCommand"));
        assertThat(inv.out(), containsString("Usage: "));
    }

    @Theory
    public void testNoCommand(Invoker inv) {
        assertEquals(1, inv.run(""));
        assertThat(inv.out(), containsString("No command specified"));
    }

    @Theory
    public void testUnknownServer(Invoker inv) {
        assertEquals(1, inv.run("list", "--server=http://127.0.0.1:9999", "--user=admin", "--password=changeme"));
        assertThat(inv.out(), containsString("java.net.ConnectException: Connection refused"));
    }

    @Theory
    public void testDebugLogging(Invoker inv) {
        assertEquals(1, inv.run("--debug", "list", "--server=http://127.0.0.1:9999", "--user=admin", "--password=changeme"));
        assertThat(inv.out(), containsString("java.net.ConnectException: Connection refused"));
        assertThat(inv.out(), containsString("Debug logging enabled."));
    }

    private static abstract class Invoker {

        private ByteArrayOutputStream _out;
        private ByteArrayOutputStream _err;
        protected PrintStream out;
        protected PrintStream err;

        public final int run(String... args) {
            _out = new ByteArrayOutputStream();
            _err = new ByteArrayOutputStream();
            out = new PrintStream(_out);
            err = new PrintStream(_err);
            return _run(args);
        }

        protected abstract int _run(String... args);

        public String out() {
            return _out.toString();
        }
    }

    private static final class WrapperInvoker extends Invoker {
        @Override public int _run(String... args) {
            try {
                String wrapper = new File("foreman-host-configurator").getCanonicalPath();
                ProcessBuilder processBuilder = new ProcessBuilder();
                List<String> command = processBuilder.command();
                command.add(wrapper);
                command.addAll(Arrays.asList(args));
                final Process proc = processBuilder.start();

                Thread outStreamReader = new Thread(new Runnable() {
                    public void run() {
                        try {
                            String line;
                            BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                            while ((line = in.readLine()) != null) {
                                out.append(line);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
                outStreamReader.start();

                // Thread that reads process error output
                Thread errStreamReader = new Thread(new Runnable() {
                    public void run() {
                        try {
                            String line;
                            BufferedReader inErr = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
                            while ((line = inErr.readLine()) != null) {
                                err.append(line);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
                errStreamReader.start();

                return proc.waitFor();
            } catch (IOException | InterruptedException e) {
                throw new Error(e);
            }
        }
    }

    private static final class MainInvoker extends Invoker {
        @Override public int _run(String... args) {
            return new Main().run(out, err, args);
        }
    }
}
