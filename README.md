# Foreman Host Configurator

The Foreman Host Configurator is a command line tool that is to be used in conjunction with the [Jenkins Foreman Node Sharing Plugin](https://wiki.jenkins-ci.org/display/JENKINS/Foreman+Node+Sharing+Plugin)

## Introduction

The **Jenkins Foreman Node Sharing** plugin is designed to shared **Bare Metal** hosts as nodes between multiple Jenkins Masters. These hosts must be first defined with specific parameters in Foreman before they can be used as Nodes in Jenkins. This tool aims to help in the creation and maintenance of these resources in Foreman.

The **Jenkins Foreman Node Sharing** plugin works by querying Foreman looking for **available** resources that meet certain requirements. They are:

- A Foreman parameter named **RESERVED** whose value is **false**
- A Foreman parameter named **JENKINS_LABEL** which contains a label that is being requested from Jenkins. For example: **docker**
- A Foreman parameter name **JENKINS_SLAVE_REMOTEFS_ROOT** which is not empty. This will be used as the node's work directory.

This tool helps create/update Foreman hosts and adds/updates/validates these hosts' parameters.

## Commands

The tool has the following commands:

- **create**

> Create is used to initially setup a list of Foreman hosts.

> It will not update any parameters is the host already exists.

> It takes an argument with points to the configuration file that defines the hosts.

- **update**

> Update is used to update a list of Foreman hosts.

> It ensures that:

> - The host exists in Foreman.
- The host is **not** reserved in Foreman prior to **updating** any attribute or setting.

  **__This prevents any changes to the host metadata while the host is being used as a shared node by a Jenkins Master.__**

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
  "defaults": {
    "parameters": [
      {
        "name": "RESERVED",
        "value": "false"
      }
    ]
  },
  "hosts": [
    {
      "name": "solaris-test-1",
      "domain_name": "example.com",
      "ip": "167.5.77.9",
      "parameters": [
        {
          "name": "JENKINS_LABEL",
          "value": "solaris9"
        },
        {
          "name": "JENKINS_SLAVE_REMOTEFS_ROOT",
          "value": "/home/jenkins"
        }
      ]
    },
    {
      "name": "solaris-test-2",
      "domain_name": "example.com",
      "ip": "167.5.77.10",
      "parameters": [
        {
          "name": "JENKINS_LABEL",
          "value": "solaris10 test"
        },
        {
          "name": "JENKINS_SLAVE_REMOTEFS_ROOT",
          "value": "/home/jenkins"
        }
      ]
    }
  ]
}
```

### Defaults section

The **defaults** section is used to denote parameters that must be created for all hosts.

```
"defaults": {
    "parameters": [
      {
        "name": "RESERVED",
        "value": "false"
      }
    ]
  },
```

For the time being, only **RESERVED** needs to be defined.

### Hosts section

The **hosts** defines the hosts to be configured.

```
{
      "name": "solaris-test-1",
      "domain_name": "example.com",
      "ip": "167.5.77.10",
      "parameters": [
        {
          "name": "JENKINS_LABEL",
          "value": "DAVE TERRY"
        },
        {
          "name": "JENKINS_SLAVE_REMOTEFS_ROOT",
          "value": "/home/jenkins"
        }
      ]
    }
```

#### Notes:

* **name**, **domain_name** and **ip** are all _mandatory_.
* **RESERVED** cannot be placed in the *parameters* section. It will be ignored.
* **name** and **value** are *mandatory* for *Parameters*

## Usage

* **create**

```
foreman-host-configurator create --server=http://localhost:3000/api --user=admin --password=changeme <path to config file>
```

* **update**

```
foreman-host-configurator update --server=http://localhost:3000/api --user=admin --password=changeme <path to config file>
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

## Initial Setup

The first time you invoke this tool, it will attempt to build itself. 

The following are needed:

- Java JDK
- Maven

## Testing

This tools also comes with a **Docker** setup that will allow one to become familiar with the tool.

```
./startForemanContainer.sh
```

This will bring up a Foreman instance that will be available at http://localhost:3000

The credentials are **admin:changeme**
