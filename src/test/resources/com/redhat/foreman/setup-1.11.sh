#!/bin/bash

DOMAIN="localdomain"
DOMAIN_ID=1

ENVIRONMENT="test_env2"
ENVIRONMENT_ID=1

ARCH="x86_64"
ARCHID=1

OPERATINGSYSTEM="Fedora"
OPERATINGSYSTEM_ID=1
MAJOR="23"
MINOR=""

PARTITION="Kickstart default"
PTABLE_ID=7
MEDIUM="Fedora Mirror"
MEDIA_ID=3

HOSTGROUP="test-group"
HOSTGROUP_ID=1

HOSTNAME="localhost"
MACADDRESS="50:7b:9d:4d:f1:39"
EXAMPLE_LABEL="label1"
JENKINS_SLAVE_REMOTEFS_ROOT="/tmp/remoteFSRoot"

USER="admin" 
PASS="changeme" 
FOREMAN_URL="http://localhost:32768/api/v2" 

domainCreateString="{ \"domain\": { \"name\": \"$DOMAIN\" } }"

envCreateString="{ \"environment\": { \"name\": \"$ENVIRONMENT\" } }"

osCreateString="{ \"operatingsystem\": { \"name\": \"$OPERATINGSYSTEM\", \"major\": \"$MAJOR\", \"minor\": \"$MINOR\", \"architecture_ids\":  $ARCHID, \"ptable_ids\": $PTABLE_ID, \"medium_ids\": $MEDIA_ID    } }"

hostGroupCreateString="{ \"hostgroup\": {  \"name\": \"$HOSTGROUP\", \"environment_id\": $ENVIRONMENT_ID, \"domain_id\": $DOMAIN_ID, \"architecture_id\":  $ARCHID, \"operatingsystem_id\": $OPERATINGSYSTEM_ID, \"medium_id\": $MEDIA_ID, \"ptable_id\": $PTABLE_ID, \"root_pass\": \"scottscott\" } }"

set -x
##hostCreateString="{ \"host\": { \"name\": \"$HOSTNAME\", \"domain_id\": $DOMAIN_ID, \"hostgroup_id\": $HOSTGROUP_ID, \"root_pass\": \"xybxa6JUkz63w\", \"mac\": \"$MACADDRESS\" , \"architecture_id\":  $ARCHID, \"operatingsystem_id\": $OPERATINGSYSTEM_ID, \"medium_id\": $MEDIA_ID, \"ptable_id\": $PTABLE_ID, \"environment_id\": $ENVIRONMENT_ID, \"parameters\": [  { \"name\": \"JENKINS_LABEL\", \"value\": \"$EXAMPLE_LABEL\" }, { \"name\": \"RESERVED\", \"value\": \"false\" }, { \"name\": \"JENKINS_SLAVE_REMOTEFS_ROOT\", \"value\": \"$JENKINS_SLAVE_REMOTEFS_ROOT\" } ] } }"
hostCreateString="{ \"host\": { \"name\": \"$HOSTNAME\", \"domain_id\": $DOMAIN_ID, \"hostgroup_id\": $HOSTGROUP_ID, \"root_pass\": \"xybxa6JUkz63w\", \"mac\": \"$MACADDRESS\" , \"architecture_id\":  $ARCHID, \"operatingsystem_id\": $OPERATINGSYSTEM_ID, \"medium_id\": $MEDIA_ID, \"ptable_id\": $PTABLE_ID, \"environment_id\": $ENVIRONMENT_ID, \"host_parameters_attributes\": [ { \"name\": \"JENKINS_LABEL\", \"value\": \"$EXAMPLE_LABEL\" } , { \"name\": \"RESERVED\", \"value\": \"false\" } , { \"name\": \"JENKINS_SLAVE_REMOTEFS_ROOT\", \"value\": \"$JENKINS_SLAVE_REMOTEFS_ROOT\" }  ] } }" 
echo ""
echo "** Creating domain $DOMAIN"
curl -g -H "Content-Type: application/json" \
	-X POST -d "$domainCreateString" \
	-k -u $USER:$PASS \
	$FOREMAN_URL/domains

echo ""
echo "** Creating environment $ENVIRONMENT"
curl -g -H "Content-Type: application/json" \
	-X POST -d "$envCreateString" \
	-k -u $USER:$PASS \
	$FOREMAN_URL/environments

echo ""
echo "** Creating operating system $OPERATINGSYSTEM $MAJOR"
curl -g -H "Content-Type: application/json" \
	-X POST -d "$osCreateString" \
	-k -u $USER:$PASS \
	$FOREMAN_URL/operatingsystems

echo ""
echo "** Creating host group $HOSTGROUP"
curl -g -H "Content-Type: application/json" \
	-X POST -d "$hostGroupCreateString" \
	-k -u $USER:$PASS \
	$FOREMAN_URL/hostgroups

echo ""
echo "** Creating host $HOSTNAME"
curl -g -H "Content-Type: application/json" \
	-X POST -d "$hostCreateString" \
	-k -u $USER:$PASS \
	$FOREMAN_URL/hosts

echo ""
echo "** Done"
echo ""
