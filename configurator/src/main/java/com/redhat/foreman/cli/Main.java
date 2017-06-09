package com.redhat.foreman.cli;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.redhat.foreman.cli.exception.ForemanApiException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by shebert on 10/01/17.
 */
public class Main {

    private static Logger LOGGER = Logger.getLogger(List.class);

    @Parameter(names = "--debug", description = "Debug mode")
    private boolean debug = false;

    @Parameter(names = "--help", help = true)
    private boolean help = false;

    @SuppressFBWarnings("DM_EXIT")
    public static void main(String... args) {
        System.exit(new Main().run(System.out, System.err, args));
    }

    public int run(PrintStream out, PrintStream err, String... args) {
        JCommander jc = new JCommander(this);
        Map<String, Command> commands = new HashMap<>();
        jc.setProgramName("foreman-host-configurator");

        ListHosts listHosts = new ListHosts();
        jc.addCommand("list", listHosts);
        commands.put("list", listHosts);

        CreateFromFile createFromFile = new CreateFromFile();
        jc.addCommand("create", createFromFile);
        commands.put("create", createFromFile);

        UpdateFromFile updateFromFile = new UpdateFromFile();
        jc.addCommand("update", updateFromFile);
        commands.put("update", updateFromFile);

        Release release = new Release();
        jc.addCommand("release", release);
        commands.put("release", release);

        StringBuilder sb = new StringBuilder();
        try {
            jc.parse(args);
        } catch (ParameterException pe) {
            LOGGER.error(pe.getMessage());
            jc.usage(sb);
            out.print(sb.toString()); // TODO STDERR
            return 3;
        }
        if (help) {
            jc.usage(sb);
            out.print(sb.toString());
            return 2;
        }
        if (debug) {
            LOGGER.getRootLogger().setLevel(Level.ALL);
            out.println("Debug logging enabled."); // TODO STDERR
        }
        try {
            String commandToRun = jc.getParsedCommand();
            if (commandToRun == null || commandToRun.equals("")) {
                throw new RuntimeException("No command specified. Run with --help to see usage.");
            }
            commands.get(commandToRun).run();
        } catch (Exception pe) {
            if (debug) {
                pe.printStackTrace(out); // TODO STDERR
            } else {
                out.println(pe.getMessage()); // TODO STDERR
            }
            if (pe instanceof ForemanApiException) {
                LOGGER.error(((ForemanApiException) pe).getDebugMessage()); // TODO STDERR
            }
            return 1;
        }
        return 0;
    }
}
