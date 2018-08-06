# Node sharing plugin for Jenkins

Share machines as Jenkins agents across multiple Jenkins masters.

https://wiki.jenkins.io/display/JENKINS/Node+Sharing+Plugin

## Architecture

The configuration of the grid is offloaded into a git repository from where both
Orchestrator and Executor pulls the configuration.

![Component Diagram](diagram.png)
