package com.redhat.foreman.cli;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.Assertion;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemOutRule;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Created by shebert on 11/01/17.
 */
public class MainTest {

    @Rule
    public final ExpectedSystemExit exit = ExpectedSystemExit.none();

    @Rule
    public final SystemOutRule systemOutRule = new SystemOutRule().enableLog();

    @Before
    public void clearLog() {
        systemOutRule.clearLog();
    }

    @Test
    public void testHelp() {
        exit.expectSystemExitWithStatus(2);
        exit.checkAssertionAfterwards(new Assertion() {
            public void checkAssertion() {
                assertThat(systemOutRule.getLog(), containsString("Usage: "));
                //
                assertThat(systemOutRule.getLog(),
                        containsString("Usage: create [options] <Files to process>"));
                //
                assertThat(systemOutRule.getLog(),
                        containsString("Usage: release [options] <the list of hosts to release>"));
                //Usage: list [options]
                assertThat(systemOutRule.getLog(), containsString("Usage: list [options]"));
                assertThat(systemOutRule.getLog(), containsString("Usage: update [options] <Files to process>"));
            }
        });
        Main.main(new String[] {"--help"});
    }

    @Test
    public void testUnknownCommand() {
        exit.expectSystemExitWithStatus(3);
        exit.checkAssertionAfterwards(new Assertion() {
            public void checkAssertion() {
                assertThat(systemOutRule.getLog(), containsString("Usage: "));
            }
        });
        Main.main(new String[] {"dummyCommand"});
    }

    @Test
    public void testNoCommand() {
        exit.expectSystemExitWithStatus(1);
        exit.checkAssertionAfterwards(new Assertion() {
            public void checkAssertion() {
                assertThat(systemOutRule.getLog(), containsString("No command specified"));
            }
        });
        Main.main(new String[] {""});
    }

    @Test
    public void testUnknownServer() {
        exit.expectSystemExitWithStatus(1);
        exit.checkAssertionAfterwards(new Assertion() {
            public void checkAssertion() {
                assertThat(systemOutRule.getLog(), containsString("java.net.ConnectException: Connection refused (Connection refused)"));
            }
        });
        Main.main(new String[] {"list", "--server=http://127.0.0.1:9999",
                "--user=admin", "--password=changeme"});
    }

    @Test
    public void testDebugLogging() {
        exit.expectSystemExitWithStatus(1);
        exit.checkAssertionAfterwards(new Assertion() {
            public void checkAssertion() {
                assertThat(systemOutRule.getLog(), containsString("java.net.ConnectException: Connection refused (Connection refused)"));
                assertThat(systemOutRule.getLog(), containsString("Debug logging enabled."));
            }
        });
        Main.main(new String[] {"--debug", "list", "--server=http://127.0.0.1:9999",
                "--user=admin", "--password=changeme"});
    }
}
