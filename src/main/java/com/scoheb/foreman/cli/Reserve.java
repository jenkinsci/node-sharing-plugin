package com.scoheb.foreman.cli;

import com.scoheb.foreman.cli.model.Host;
import org.apache.log4j.Logger;

import java.util.List;

public class Reserve {

    private static Logger LOGGER = Logger.getLogger(List.class);

    public static void main(String[] args) {
        String user = "admin";
        String password = "changeme";

        Api api = new Api("http://localhost:3000/api/", user, password);
        Host h = api.getHost("stage1.scoheb.com");
        api.reserveHost(h, "http://localhost:8080");
    }
}
