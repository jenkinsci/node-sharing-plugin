package com.scoheb.foreman.cli;

import com.beust.jcommander.Parameters;
import com.scoheb.foreman.cli.exception.ForemanApiException;
import com.scoheb.foreman.cli.model.Host;
import com.scoheb.foreman.cli.model.Hosts;
import com.scoheb.foreman.cli.model.Parameter;
import com.scoheb.foreman.cli.model.Reservation;
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
            LOGGER.info("Updating " + h.name);
            Host hostObj = api.getHost(h.name);
            if (hostObj == null) {
                throw new RuntimeException("Host " + h.name + " DOES NOT EXIST");
            }
            Reservation reservation = api.getHostReservation(hostObj);
            if (!(reservation instanceof Reservation.EmptyReservation)) {
                LOGGER.info("Host " + hostObj.name + " is reserved (" + reservation.reason + "). Will update...");
                updateHostParameters(api, h, hostObj);
            } else {
                LOGGER.info("Host " + hostObj.name + " is NOT reserved. Will attempt to reserve before updating...");
                String reserveMsg = api.reserveHost(hostObj, "Reserved by Foreman Host Configurator to perform update.");
                reservation = api.getHostReservation(hostObj);
                if (reservation instanceof Reservation.EmptyReservation) {
                    throw new ForemanApiException("Failed to reserve host: " + hostObj.name, reserveMsg);
                }
                LOGGER.info("Host " + hostObj.name + " is NOW reserved (" + reservation.reason + "). Will update...");
                updateHostParameters(api, h, hostObj);
                String releaseMsg = api.releaseHost(hostObj);
                reservation = api.getHostReservation(hostObj);
                if (!(reservation instanceof Reservation.EmptyReservation)) {
                    throw new ForemanApiException("Failed to release host: " + hostObj.name, releaseMsg);
                }
                LOGGER.info("Host " + hostObj.name + " has been released.");
            }
        }
    }

    private void updateHostParameters(Api api, Host h, Host hostObj) throws ForemanApiException {
        if (h.parameters != null && h.parameters.size() > 0) {
            for (Parameter p: h.parameters) {
                if (p.name.equals("RESERVED")) {
                    LOGGER.warn("The parameter RESERVED cannot be updated via this commmand." +
                            " You must use the 'release' command.");
                    continue;
                }
                api.updateHostParameter(hostObj, p);
                LOGGER.info("Added/Updated parameter " + p.name + " to be '" + p.value + "'");
            }
        }
    }

}
