package com.scoheb.foreman.cli;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.Assertion;
import org.junit.contrib.java.lang.system.ExpectedSystemExit;
import org.junit.contrib.java.lang.system.SystemOutRule;

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
                System.out.println(systemOutRule.getLog());
                assertTrue(systemOutRule.getLog().indexOf("Usage: ") >= 0);
                //
                assertTrue(systemOutRule.getLog()
                        .indexOf("Usage: create [options] <Files to process>") >= 0);
                //
                assertTrue(systemOutRule.getLog()
                        .indexOf("Usage: release [options] <the list of hosts to release>") >= 0);
                //Usage: list [options]
                assertTrue(systemOutRule.getLog()
                        .indexOf("Usage: list [options]") >= 0);
                assertTrue(systemOutRule.getLog()
                        .indexOf("Usage: update [options] <Files to process>") >= 0);
            }
        });
        Main.main(new String[] {"--help"});
    }

    @Test
    public void testUnknownCommand() {
        exit.expectSystemExitWithStatus(3);
        exit.checkAssertionAfterwards(new Assertion() {
            public void checkAssertion() {
                assertTrue(systemOutRule.getLog().indexOf("Usage: ") >= 0);
            }
        });
        Main.main(new String[] {"dummyCommand"});
    }

    @Test
    public void testNoCommand() {
        exit.expectSystemExitWithStatus(1);
        exit.checkAssertionAfterwards(new Assertion() {
            public void checkAssertion() {
                assertTrue(systemOutRule.getLog().indexOf("No command specified") >= 0);
            }
        });
        Main.main(new String[] {""});
    }

    @Test
    public void testUnknownServer() {
        exit.expectSystemExitWithStatus(1);
        exit.checkAssertionAfterwards(new Assertion() {
            public void checkAssertion() {
                assertTrue(systemOutRule.getLog().indexOf("java.net.ConnectException: Connection refused (Connection refused)") >= 0);
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
                assertTrue(systemOutRule.getLog().indexOf("java.net.ConnectException: Connection refused (Connection refused)") >= 0);
                assertTrue(systemOutRule.getLog().indexOf("Debug logging enabled.") >= 0);
            }
        });
        Main.main(new String[] {"--debug", "list", "--server=http://127.0.0.1:9999",
                "--user=admin", "--password=changeme"});
    }
}
