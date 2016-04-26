package com.redhat.foreman.integration.po;

import org.jenkinsci.test.acceptance.plugins.ssh_credentials.SshCredentialDialog;
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

    /**
     * Control for credentials.
     */
    private final Control credentialsId = control("credentialsId");
    private String name;

    /**
     * Constructor.
     * @param context page area.
     * @param path path of pa.
     */
    public ForemanCloudPageArea(PageObject context, String path) {
        super(context, path);
    }

    /**
     * Set name.
     * @param value name.
     * @return ForemanCloudPageArea.
     */
    public ForemanCloudPageArea name(String value) {
        control("cloudName").set(value);
        this.name = value;
        return this;
    }

    /**
     * Set user.
     * @param value user.
     * @return ForemanCloudPageArea.
     */
    public ForemanCloudPageArea user(String value) {
        control("user").set(value);
        return this;
    }

    /**
     * Set url.
     * @param value url.
     * @return ForemanCloudPageArea.
     */
    public ForemanCloudPageArea url(String value) {
        control("url").set(value);
        return this;
    }

    /**
     * Set password.
     * @param value password.
     * @return ForemanCloudPageArea.
     */
    public ForemanCloudPageArea password(String value) {
        control("password").set(value);
        return this;
    }

    /**
     * Test connection.
     * @return ForemanCloudPageArea.
     */
    public ForemanCloudPageArea testConnection() {
        clickButton("Test Connection");
        return this;
    }

    /**
     * Add credential.
     * @return dialog.
     */
    public SshCredentialDialog addCredential() {
        self().findElement(by.button("Add")).click();

        return new SshCredentialDialog(getPage(), "/credentials");
    }

    /**
     * Set credentials.
     * @param string credentials.
     * @return ForemanCloudPageArea.
     */
    public ForemanCloudPageArea setCredentials(String string) {
        credentialsId.select(string);
        return this;
    }

    /**
     * Checks for compatible hosts.
     * @return ForemanCloudPageArea.
     */
    public ForemanCloudPageArea checkForCompatibleHosts() {
        clickButton("Check for Compatible Foreman Hosts");
        return this;
    }

    /**
     * Get Cloud name.
     * @return name.
     */
    public String getCloudName() {
        return name;
    }


}
