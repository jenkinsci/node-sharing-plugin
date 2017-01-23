package com.scoheb.foreman.cli;

import com.scoheb.foreman.cli.exception.ForemanApiException;
import com.scoheb.foreman.cli.model.Host;
import com.scoheb.foreman.cli.model.Parameter;

/**
 * Created by shebert on 20/01/17.
 */
public class TestCreate {
    public static void main(String[] args) throws ForemanApiException {
        String url = "http://localhost:3000/api";
        String user = "admin";
        String password = "changeme";
        Api api = new Api(url, user, password);
        //Domain domain = api.createDomain("localhost");
        //Host host = api.createHost("scott", domain);
        Host host = api.createHost("scott.localdomain");
        Parameter parameter = api.updateHostParameter(host, new Parameter("RESERVED", "true"));
    }
}

