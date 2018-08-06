package com.redhat.jenkins.nodesharingbackend.Dashboard

import com.redhat.jenkins.nodesharing.ConfigRepo
import com.redhat.jenkins.nodesharing.ConfigRepoAdminMonitor
import com.redhat.jenkins.nodesharingbackend.Dashboard
import com.redhat.jenkins.nodesharingbackend.Pool
import com.redhat.jenkins.nodesharingbackend.Pool.PoolMisconfigured

Dashboard d = my

h1(d.displayName)

Pool pool = Pool.getInstance()
try {
    ConfigRepo.Snapshot snapshot = pool.getConfig()
    p { text("Controlled by config repo at ${pool.getConfigRepoUrl()}") }
    table {
        tr {
            th {
                text("Executor Jenkins")
            }
        }
        snapshot.jenkinses.each { executor ->
            tr {
                td {
                    a(href: executor.url) {
                        text(executor.name)
                    }
                }
            }
        }
    }
} catch (PoolMisconfigured ex) {
    div(class: "error") {
        a(href: ConfigRepoAdminMonitor.instance.url) {
            text(ex.message)
        }
    }
}
