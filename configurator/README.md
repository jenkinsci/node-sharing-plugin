# Foreman Host Configurator

The Foreman Host Configurator is a command line tool that is to be used in conjunction with the [Jenkins Foreman Node Sharing Plugin](https://wiki.jenkins-ci.org/display/JENKINS/Foreman+Node+Sharing+Plugin)

## Introduction

The **Jenkins Foreman Node Sharing** plugin is designed to shared **Bare Metal** hosts as nodes between multiple Jenkins Masters. These hosts must be first defined with specific parameters in Foreman before they can be used as Nodes in Jenkins. This tool aims to help in the creation and maintenance of these resources in Foreman.

This tool helps create/update Foreman hosts and adds/updates/validates these hosts' parameters.

## Commands

The tool has the following commands:

- **create**

> Create is used to initially setup a list of Foreman hosts.

> It will not update any parameters if the host already exists.

> It takes an argument with points to the configuration file that defines the hosts.

- **update**

> Update is used to update a list of Foreman hosts.

> It ensures that:

> - The host exists in Foreman.
- The host **is reserved** in Foreman prior to **updating** any attribute or setting.

  **__This ensures that the host is not being reserved WHILE it is being updated by this application.__**

- **list**

> List is to be used to query a list of hosts within Foreman.

> It can take a query as an argument.

- **release**

> Release is used to release (or un-reserve) a set of hosts.

> This is to be used in the event that Jenkins did not release the node itself during its normal cloud lifecyle operation.

## Configuration File Format

Here is an example of a configuration file that can be used for the **create** and **update** commands:

```
{
  "hosts": [
    {
      "name": "solaris-test-1.example.com",
      "labels": "solaris9",
      "remoteFs": "/home/jenkins"
    },
    {
      "name": "solaris-test-2.example.com",
      "labels": "solaris10 test",
      "remoteFs": "/home/jenkins"
    }
  ]
}
```

### Hosts section

The **hosts** defines the hosts to be configured.

```
{
      "name": "solaris-test-1.example.com",
      "labels": "solaris10 test",
      "remoteFs": "/home/jenkins"
}
```

#### Notes:

* **name** is _mandatory_.
* **labels** and **remoteFs** are *optional*

### Variables

You may also reference variables that can be specified by a properties file. For example, you can specify:

```
foreman-host-configurator ... --properties=<path to properties file>
```

For more details of the properties file, please see: [Properties File Format](https://docs.oracle.com/cd/E23095_01/Platform.93/ATGProgGuide/html/s0204propertiesfileformat01.html)

Taking the above config file snippet, variable can be referenced as follows:

```
{
      "name": "solaris-test-1${EXAMPLEDOMAIN}",
      "labels": "${SOLARISLABEL} test",
      "remoteFs": "${FSROOT}"
}
```

with a corresponding properties file of:

```
EXAMPLEDOMAIN=.example.com
SOLARISLABEL=solaris10
FSROOT=/home/jenkins
```

## Usage

* **create**

```
foreman-host-configurator create --server=http://localhost:3000/api --user=admin --password=changeme [--properties=<path to properties file>] <path to config file>
```

* **update**

```
foreman-host-configurator update --server=http://localhost:3000/api --user=admin --password=changeme [--properties=<path to properties file>] <path to config file>
```

* **list**

```
foreman-host-configurator list --server=http://localhost:3000/api --user=admin --password=changeme --query="name = solaris1.example.com"
```

example queries

> more examples can be found at [Searching in Foreman](https://theforeman.org/manuals/1.14/index.html#4.1.5Searching)

- name ~ solaris
  - This matches all hosts whose name contains solaris
- params.JENKINS_LABEL = \\"solaris10 debug\\"
  - This matches all hosts whose parameter called JENKINS_LABEL equals "solaris10 debug"
  - **Note that you must escape the \" in the query using \\".**

* **release**

```
foreman-host-configurator release --server=http://localhost:3000/api --user=admin --password=changeme --force solaris1.example.com
```

## Initial Setup

The first time you invoke this tool, it will attempt to build itself.

The following are needed:

- Java JDK
- Maven

## Testing

This tools also comes with a **Docker** setup that will allow one to become familiar with the tool.

```
./scripts/startForemanContainer.sh
```

This will bring up a Foreman instance that will be available at http://localhost:3000

The credentials are **admin:changeme**
