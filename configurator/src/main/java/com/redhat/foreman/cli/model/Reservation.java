package com.redhat.foreman.cli.model;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Created by shebert on 17/01/17.
 */
public class Reservation {
    private final String reason;

    public Reservation(@Nonnull final String reservation) {
        this.reason = reservation;
    }

    @Nonnull
    public static Reservation none() {
        return new EmptyReservation("");
    }

    @CheckForNull
    public String getReason() {
        return reason;
    }

    public static class EmptyReservation extends Reservation {
        public EmptyReservation(@Nonnull final String reservation) {
            super(reservation);
        }
    }
}
