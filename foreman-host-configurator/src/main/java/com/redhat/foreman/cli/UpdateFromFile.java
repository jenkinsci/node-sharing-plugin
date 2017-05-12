package com.redhat.foreman.cli;

import com.beust.jcommander.Parameters;
import com.redhat.foreman.cli.exception.ForemanApiException;
import com.redhat.foreman.cli.model.Host;
import com.redhat.foreman.cli.model.Hosts;
import com.redhat.foreman.cli.model.Parameter;
import com.redhat.foreman.cli.model.Reservation;
import org.apache.log4j.Logger;

import java.util.List;

@Parameters(separators = "=", commandDescription = "Update Hosts in Foreman from file")
public class UpdateFromFile extends AbstractFileProcessor {

    private static Logger LOGGER = Logger.getLogger(UpdateFromFile.class);

    public UpdateFromFile(List<String> files) {
        this.files = files;
    }

    public UpdateFromFile() {
    }

    @Override
    public void perform(Hosts hosts) throws ForemanApiException {
        Api api = new Api(server, user, password);
        for (Host h: hosts.getHosts()) {
            checkHostAttributes(h);
            LOGGER.info("Updating " + h.getName());
            Host hostObj = api.getHost(h.getName());
            if (hostObj == null) {
                throw new RuntimeException("Host " + h.getName() + " DOES NOT EXIST");
            }
            Reservation reservation = api.getHostReservation(hostObj);
            if (!(reservation instanceof Reservation.EmptyReservation)) {
                LOGGER.info("Host " + hostObj.getName() + " is reserved (" + reservation.getReason() + "). Will update...");
                updateHostParameters(api, h, hostObj);
            } else {
                LOGGER.info("Host " + hostObj.getName() + " is NOT reserved. Will attempt to reserve before updating...");
                String reserveMsg = api.reserveHost(hostObj, "Reserved by Foreman Host Configurator to perform update.");
                reservation = api.getHostReservation(hostObj);
                if (reservation instanceof Reservation.EmptyReservation) {
                    throw new ForemanApiException("Failed to reserve host: " + hostObj.getName(), reserveMsg);
                }
                LOGGER.info("Host " + hostObj.getName() + " is NOW reserved (" + reservation.getReason() + "). Will update...");
                updateHostParameters(api, h, hostObj);
                String releaseMsg = api.releaseHost(hostObj);
                reservation = api.getHostReservation(hostObj);
                if (!(reservation instanceof Reservation.EmptyReservation)) {
                    throw new ForemanApiException("Failed to release host: " + hostObj.getName(), releaseMsg);
                }
                LOGGER.info("Host " + hostObj.getName() + " has been released.");
            }
        }
    }

    private void updateHostParameters(Api api, Host h, Host hostObj) throws ForemanApiException {
        if (h.parameters != null && h.parameters.size() > 0) {
            for (Parameter p: h.parameters) {
                if (p.getName().equals("RESERVED")) {
                    LOGGER.warn("The parameter RESERVED cannot be updated via this commmand." +
                            " You must use the 'release' command.");
                    continue;
                }
                api.updateHostParameter(hostObj, p);
                LOGGER.info("Added/Updated parameter " + p.getName() + " to be '" + p.getValue() + "'");
            }
        }
    }

}
