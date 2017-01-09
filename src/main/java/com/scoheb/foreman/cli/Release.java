package com.scoheb.foreman.cli;

import com.scoheb.foreman.cli.model.Environment;
import com.scoheb.foreman.cli.model.Host;
import com.scoheb.foreman.cli.model.Hostgroup;
import com.scoheb.foreman.cli.model.Parameter;
import org.apache.log4j.Logger;

import java.util.List;

public class Release {

    private static Logger LOGGER = Logger.getLogger(List.class);

    public static void main(String[] args) {
        String user = "admin";
        String password = "changeme";

        Api api = new Api("http://localhost:3000/api/", user, password);
        Host h = api.getHost("stage1.scoheb.com");
        api.releaseHost(h);

    }
}
