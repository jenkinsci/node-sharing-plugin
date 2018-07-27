package com.redhat.jenkins.nodesharingbackend.Dashboard

import com.redhat.jenkins.nodesharingbackend.Dashboard

Dashboard d = my

h1("Node Sharing Pool Orchestrator")

table {
    tr {
        th {
            text("Executor Jenkins")
        }
    }
    d.configSnapshot.jenkinses.each { executor ->
        tr {
            td {
                a(href: executor.url) {
                    text(executor.name)
                }
            }
        }
    }
}
