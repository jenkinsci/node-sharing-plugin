# Foreman Container

## Usage

* docker build -t jenkins/foreman .
* docker run -d -p "3000:3000" jenkins/foreman
* login using admin/changeme

## Important Note

The Dockerfile and its components are currently used for testing of the
Foreman Host Configurator.

In addition, it has been replicated to the
[Jenkins Acceptance Test Harness repository](https://github.com/jenkinsci/acceptance-test-harness)
where it is used by the Foreman Node Sharing Plugin tests.

It is imperative that any changes to this file be made in this folder and then
replicated to the ATH repo afterwards.
