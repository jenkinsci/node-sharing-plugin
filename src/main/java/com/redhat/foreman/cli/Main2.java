package com.redhat.foreman.cli;

import com.google.gson.Gson;
import com.scoheb.foreman.cli.model.Domain;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

public class Main2 {

    public static void main(String[] args) {

        try {
            Domain d = new Domain();
            Gson gson = new Gson();
            String json = gson.toJson(d);
            System.out.println(json);

            String user = "admin";
            String password = "changeme";

            ClientConfig clientConfig = new ClientConfig();
            HttpAuthenticationFeature feature = HttpAuthenticationFeature.basic(user, password);
            clientConfig.register(feature);
            Client client = ClientBuilder.newClient(clientConfig);

            WebTarget target = client
                    .target("http://localhost:3000/api/v2/domains");

            Response testR = target.path("/").request(MediaType
                    .APPLICATION_JSON).get();
            System.out.println("Existing domains:");
            String testRAsString = testR.readEntity(String.class);
            System.out.println(testRAsString);

            Response response =
                    target.request(MediaType.APPLICATION_JSON)
                            .post(Entity.entity(json, MediaType.APPLICATION_JSON));

            if (response.getStatus() != 201) {
                throw new RuntimeException("Failed : HTTP error code : "
                        + response.getStatus());
            }

            System.out.println("Output from Server:");
            String responseAsString = response.readEntity(String.class);
            System.out.println(responseAsString);
        } catch (Exception ex) {
            System.err.println(ex.getMessage());
        } finally {
        }
    }
}
