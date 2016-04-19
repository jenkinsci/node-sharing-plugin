#!/bin/bash

set -x

DOMAIN="Scott2"
domainCreateString="{ \"domain\": { \"name\": \"$DOMAIN\" } }"

ENVIRONMENT="test_env2"
envCreateString="{ \"environment\": { \"name\": \"$ENVIRONMENT\" } }"

OPERATINGSYSTEM="Fedora"
MAJOR="22"
MINOR=""
osCreateString="{ \"operatingsystem\": { \"name\": \"$OPERATINGSYSTEM\", \"major\": \"$MAJOR\", \"minor\": \"$MINOR\" } }"

HOSTGROUP="my-group-4"
HOSTGROUP="test-group"
ARCH="x86_64"
MEDIUM="Fedora Mirror"
PARTITION="Kickstart default"
hostGroupCreateString="{ \"hostgroup\": {  \"name\": \"$HOSTGROUP\", \"environment_name\": \"$ENVIRONMENT\", \"domain_name\": \"$DOMAIN\", \"architecture_name\": \"$ARCH\", \"operatingsystem_name\": \"$OPERATINGSYSTEM $MAJOR\", \"medium_name\": \"$MEDIUM\", \"ptable_name\": \"$PARTITION\"  } , \"root_pass\": \"scottscott\" }"

HOSTNAME="scott"
MACADDRESS="50:7b:9d:4d:f1:37"
hostCreateString="{ \"host\": { \"name\": \"$HOSTNAME\", \"hostgroup_name\": \"$HOSTGROUP\", \"root_pass\": \"xybxa6JUkz63w\", \"mac\": \"$MACADDRESS\" } }"


USER="admin" 
PASS="changeme" 
FOREMAN_URL="http://localhost:32768/api/v2" 

curl -g -H "Content-Type: application/json" \
	-X POST -d "$domainCreateString" \
	-k -u $USER:$PASS \
	$FOREMAN_URL/domains

curl -g -H "Content-Type: application/json" \
	-X POST -d "$envCreateString" \
	-k -u $USER:$PASS \
	$FOREMAN_URL/environments

curl -g -H "Content-Type: application/json" \
	-X POST -d "$osCreateString" \
	-k -u $USER:$PASS \
	$FOREMAN_URL/operatingsystems

curl -g -H "Content-Type: application/json" \
	-X POST -d "$hostGroupCreateString" \
	-k -u $USER:$PASS \
	$FOREMAN_URL/hostgroups

curl -g -H "Content-Type: application/json" \
	-X POST -d "$hostCreateString" \
	-k -u $USER:$PASS \
	$FOREMAN_URL/hosts


