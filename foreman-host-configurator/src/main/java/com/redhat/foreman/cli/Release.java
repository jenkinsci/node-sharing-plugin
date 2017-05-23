package com.redhat.foreman.cli;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.redhat.foreman.cli.exception.ForemanApiException;
import com.redhat.foreman.cli.model.Host;
import com.redhat.foreman.cli.model.Reservation;
import org.apache.log4j.Logger;

import java.util.List;

@Parameters(separators = "=", commandDescription = "Release hosts in Foreman")
public class Release extends Command {

    @com.beust.jcommander.Parameter(description = "<the list of hosts to release>"
            , required = true)
    private List<String> hosts;

    public void setForce(boolean force) {
        this.force = force;
    }

    public Release(List<String> hosts) {
        this.hosts = hosts;
    }

    public Release() {
    }

    @Parameter(names = "--force",
            description = "Force the release of the host")
    protected boolean force;

    private static Logger LOGGER = Logger.getLogger(List.class);

    @Override
    public void run() throws ForemanApiException {
        if (hosts == null || hosts.size() == 0) {
            LOGGER.info("No hosts to release");
            return;
        }
        Api api = new Api(server, user, password);
        for (String h: hosts) {
            Host hostObj = api.getHost(h);
            if (hostObj == null) {
                throw new RuntimeException("Host " + h + " not found");
            }
            Reservation reservation = api.getHostReservation(hostObj);
            if (reservation instanceof Reservation.EmptyReservation) {
                LOGGER.info("Host " + hostObj.getName() + " not reserved...");
                continue;
            }
            if (force) {
                LOGGER.info("Host " + hostObj.getName() + " reserved by:" + reservation.getReason());
                LOGGER.info("Force is set...releasing!");
                api.releaseHost(hostObj);
            } else {
                LOGGER.info("Host " + hostObj.getName() + " already reserved by: " + reservation.getReason());
                LOGGER.info("and --force not set. Will not release");
            }
        }
    }
}
