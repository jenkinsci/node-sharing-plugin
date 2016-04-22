package com.redhat.foreman.integration.po;

import org.jenkinsci.test.acceptance.po.Cloud;
import org.jenkinsci.test.acceptance.po.Describable;
import org.jenkinsci.test.acceptance.po.PageObject;

/**
 * Foreman Cloud.
 *
 */
@Describable("Foreman")
public class ForemanCloudPageArea extends Cloud {

    public ForemanCloudPageArea(PageObject context, String path) {
        super(context, path);
    }

    public ForemanCloudPageArea name(String value) {
        control("cloudName").set(value);
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


}
