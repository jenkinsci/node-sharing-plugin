package com.redhat.jenkins.nodesharing.transport;

import hudson.model.Queue;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;

public class ReportWorkloadRequest extends ExecutorEntity {

    @Nonnull private final Workload workload;

    public ReportWorkloadRequest(@Nonnull Fingerprint fingerprint, Workload workload) {
        super(fingerprint);
        this.workload = workload;
    }

    public @Nonnull Workload getWorkload() {
        return this.workload;
    }

    // TODO not immutable - change to request builder?
    public static final class Workload {

        private List<WorkloadItem> items = new ArrayList<>();

        public Workload() {}

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

        public static final class WorkloadItem {

            private final long id;

            private final String name;

            @Restricted(NoExternalUse.class)
            public WorkloadItem(final long id, @Nonnull final String name) {
                this.id = id;
                this.name = name;
            }

            public WorkloadItem(@Nonnull final Queue.Item item) {
                this.id = item.getId();
                this.name = item.task.getFullDisplayName();
            }
            public long getId() {
                return id;
            }

            public String getName() {
                return name;
            }
        }
    }
}
