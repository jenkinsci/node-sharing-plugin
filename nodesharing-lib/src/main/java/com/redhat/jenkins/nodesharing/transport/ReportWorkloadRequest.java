package com.redhat.jenkins.nodesharing.transport;

import hudson.model.Label;
import hudson.model.Queue;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ReportWorkloadRequest extends ExecutorEntity {

    @Nonnull private final Workload workload;

    public ReportWorkloadRequest(@Nonnull Fingerprint fingerprint, @Nonnull Workload workload) {
        super(fingerprint);
        this.workload = workload;
    }

    public @Nonnull Workload getWorkload() {
        return this.workload;
    }

    public static final class Workload {
        private final @Nonnull List<WorkloadItem> items;

        private Workload(List<WorkloadItem> items) {
            this.items = new ArrayList<>(items);
        }

        public long size() {
            return items.size();
        }

        public @Nonnull List<WorkloadItem> getItems() {
            return items;
        }

        public static @Nonnull WorkloadBuilder builder() {
            return new WorkloadBuilder();
        }

        public static final class WorkloadBuilder {
            private List<WorkloadItem> items;

            public WorkloadBuilder() {
                this.items = new ArrayList<>();
            }

            public WorkloadBuilder(final List<WorkloadItem> items) {
                this.items = items;
            }

            public void addItem(@Nonnull final Queue.Item item) {
                items.add(new WorkloadItem(item));
            }

            public Workload build() {
                return new Workload(items);
            }
        }

        public static final class WorkloadItem {

            private final long id;
            private final @Nonnull String name;
            private final @Nonnull String labelExpr;

            @Restricted(NoExternalUse.class)
            public WorkloadItem(final long id, @Nonnull final String name, @Nonnull String labelExpr) {
                this.id = id;
                this.name = name;
                this.labelExpr = labelExpr;
            }

            public WorkloadItem(@Nonnull final Queue.Item item) {
                this.id = item.getId();
                this.name = item.task.getFullDisplayName();
                this.labelExpr = item.getAssignedLabel().toString();
            }

            public long getId() {
                return id;
            }

            public @Nonnull String getName() {
                return name;
            }

            public @Nonnull String getLabelExpr() {
                return labelExpr;
            }

            public @Nonnull Label getLabel() {
                return Label.get(labelExpr);
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                WorkloadItem that = (WorkloadItem) o;
                return id == that.id
                        && Objects.equals(name, that.name)
                        && Objects.equals(labelExpr, that.labelExpr)
                ;
            }

            @Override
            public int hashCode() {
                return Objects.hash(id, name, labelExpr);
            }
        }
    }
}
