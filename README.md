# Jenkins Node Sharing using Foreman

This repo contains the following 3 tools that enable sharing nodes in Jenkins using Foreman

- Foreman container (used for testing and development)
- Foreman Host Configurator
- Jenkins Foreman Node Sharing plugin

## Introduction

The **Jenkins Foreman Node Sharing** plugin is designed to shared **Bare Metal** hosts as nodes between multiple Jenkins Masters.

These hosts must be first defined with specific parameters in Foreman before they can be used as Nodes in Jenkins.

The **Foreman Host Configurator** tool aims to help in the creation and maintenance of these resources in Foreman.

The **Jenkins Foreman Node Sharing** plugin works by querying Foreman looking for **available** resources that meet certain requirements. They are:

- A Foreman parameter named **RESERVED** whose value is **false**
- A Foreman parameter named **JENKINS_LABEL** which contains a label that is being requested from Jenkins. For example: **docker**
- A Foreman parameter name **JENKINS_SLAVE_REMOTEFS_ROOT** which is not empty. This will be used as the node's work directory.
