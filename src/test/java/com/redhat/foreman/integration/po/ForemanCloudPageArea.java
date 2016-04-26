package com.redhat.foreman.integration.po;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.jenkinsci.test.acceptance.plugins.ssh_credentials.SshCredentialDialog;
import org.jenkinsci.test.acceptance.plugins.ssh_credentials.SshPrivateKeyCredential;
import org.jenkinsci.test.acceptance.po.Cloud;
import org.jenkinsci.test.acceptance.po.Control;
import org.jenkinsci.test.acceptance.po.Describable;
import org.jenkinsci.test.acceptance.po.PageObject;

/**
 * Foreman Cloud.
 *
 */
@Describable("Foreman")
public class ForemanCloudPageArea extends Cloud {

    public final Control credentialsId = control("credentialsId");
    private String name;

    public ForemanCloudPageArea(PageObject context, String path) {
        super(context, path);
    }

    public ForemanCloudPageArea name(String value) {
        control("cloudName").set(value);
        this.name = value;
        return this;
    }

    public ForemanCloudPageArea user(String value) {
        control("user").set(value);
        return this;
    }

    public ForemanCloudPageArea url(String value) {
        control("url").set(value);
        return this;
    }

    public ForemanCloudPageArea password(String value) {
        control("password").set(value);
        return this;
    }

    public ForemanCloudPageArea testConnection() {
        clickButton("Test Connection");
        return this;
    }

    /**
     * Once a credential has been created for a given slave, this method can be used 
     * to check whether it has already been rendered in the dropdown.
     */
    private void waitForCredentialVisible(final String credUsername) {
        waitFor().withTimeout(5, TimeUnit.SECONDS).until(new Callable<Boolean>() {
            @Override
            public Boolean call() throws Exception {
                return credentialsId.resolve().getText().contains(credUsername);
            }
        });
    }

    public SshCredentialDialog addCredential() {
        self().findElement(by.button("Add")).click();

        return new SshCredentialDialog(getPage(), "/credentials");
    }

    public ForemanCloudPageArea addCredentials(String username) {
        SshCredentialDialog dia = this.addCredential();
        waitForCredentialVisible(username);
        return this;
    }

    public ForemanCloudPageArea setCredentials(String string) {
        credentialsId.select(string);
        return this;
    }

    public ForemanCloudPageArea checkForCompatibleHosts() {
        clickButton("Check for Compatible Foreman Hosts");
        return this;
    }

    public String getCloudName() {
        return name;
    }


}
