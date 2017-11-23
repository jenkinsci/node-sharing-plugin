package com.redhat.jenkins.nodesharing;

import hudson.model.Queue;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class Workload {

    private List<WorkloadItem> items = new ArrayList<WorkloadItem>();

    public Workload() {}

    @DataBoundConstructor
    public Workload(List<WorkloadItem> items) {
        this.items = items;
    }

    public void addItem(@Nonnull final Queue.Item item) {
        items.add(new WorkloadItem(item));
    }

    public long size() {
        return items.size();
    }

    public List<WorkloadItem> getItems() {
        return new ArrayList<WorkloadItem>(items);
    }

    public class WorkloadItem {

        private final long id;

        private final String name;

        @DataBoundConstructor
        public WorkloadItem(@Nonnull final Queue.Item item) {
            this.id = item.getId();
            this.name = item.getDisplayName();
        }
        public long getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }
}
