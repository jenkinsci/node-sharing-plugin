#!/bin/bash

set +x

DOMAIN="localdomain"
ENVIRONMENT="test_env2"

ARCH="x86_64"

OPERATINGSYSTEM="Fedora"
MAJOR="23"
MINOR=""

PARTITIONTABLE="Kickstart default"
MEDIUM="Fedora mirror"

HOSTGROUP="test-group"

HOSTNAME="localhost"
MACADDRESS="50:7b:9d:4d:f1:39"
EXAMPLE_LABEL="label1"
JENKINS_SLAVE_REMOTEFS_ROOT="/tmp/remoteFSRoot"

USER="admin" 
PASS="changeme" 
FOREMAN_URL="http://localhost:32768/" 

check_for_hammer_foreman()
{
    HAMMERCHECK=`which hammer`
    if [ -z "$HAMMERCHECK" ]; then
      return 1
    else
      HAMMERFOREMANCHECK=`hammer -h | grep -e puppet`
      if [ -z "$HAMMERFOREMANCHECK" ]; then
        return 1
      else
        return 0
      fi
    fi
}

check_for_entity()
{
    entity=$1
    name=$2
    value=""
    check=`hammer -u $USER -p $PASS -s $FOREMAN_URL $entity list --search "name = \"$name\"" | grep -v '^---' | grep -v ^ID | head -1 | awk '{print $1}'`
    echo $check
}

create_object() {
    entity=$1
    command=$2
    check=`hammer $command`
    status=$?
    echo $status
}

if [ `check_for_hammer_foreman` ] ; then
  echo "ERROR: Hammer and/or Hammer Foreman not installed!"
  exit 1
fi

echo ""
ARCHID=`check_for_entity architecture $ARCH`
if [ -z "$ARCHID" ] ; then
     echo "ERROR: architecture $ARCH does not exist!"
     exit 1
else
  echo "** architecture $ARCH ($ARCHID) exists"
fi

echo ""
PARTITIONTABLEID=`check_for_entity partition-table "$PARTITIONTABLE"`
if [ -z "$PARTITIONTABLEID" ] ; then
     echo "ERROR: partition-table $PARTITIONTABLE does not exist!"
     exit 1
else
  echo "** partition-table $PARTITIONTABLE ($PARTITIONTABLEID) exists"
fi

echo ""
MEDIUMID=`check_for_entity medium "$MEDIUM"`
if [ -z "$MEDIUMID" ] ; then
     echo "ERROR: medium $MEDIUM does not exist!"
     exit 1
else
  echo "** medium $MEDIUM ($MEDIUMID) exists"
fi

echo ""
DOMAINID=`check_for_entity domain $DOMAIN`
if [ -z "$DOMAINID" ] ; then
  echo "** Creating domain $DOMAIN"
  if [ `create_object domain "domain create --name $DOMAIN"` -eq 0 ] ; then
     DOMAINID=`check_for_entity domain $DOMAIN`
     echo -e "\t** Created domain $DOMAIN ($DOMAINID)"
  else
     echo "ERROR: domain $DOMAIN creation failed!"
     exit 1
  fi
else
  echo "** domain $DOMAIN ($DOMAINID) exists"
fi

echo ""
ENVIRONMENTID=`check_for_entity environment $ENVIRONMENT`
if [ -z "$ENVIRONMENTID" ] ; then
  echo "** Creating environment $ENVIRONMENT"
  if [ `create_object environment "environment create --name $ENVIRONMENT"` -eq 0 ] ; then
     ENVIRONMENTID=`check_for_entity environment $ENVIRONMENT`
     echo -e "\t** Created environment $ENVIRONMENT ($ENVIRONMENTID)"
  else
     echo "ERROR: environment $ENVIRONMENT creation failed!"
     exit 1
  fi
else
  echo "** environment $ENVIRONMENT ($ENVIRONMENTID) exists"
fi

echo ""
OPERATINGSYSTEMID=`check_for_entity os $OPERATINGSYSTEM`
if [ -z "$OPERATINGSYSTEMID" ] ; then
  echo "** Creating os $OPERATINGSYSTEM $MAJOR"
  if [ `create_object os "os create --name $OPERATINGSYSTEM --major $MAJOR"` -eq 0 ] ; then
     OPERATINGSYSTEMID=`check_for_entity os $OPERATINGSYSTEM`
     echo -e "\t** Created os $OPERATINGSYSTEM $MAJOR ($OPERATINGSYSTEMID)"
  else
     echo "ERROR: os $OPERATINGSYSTEM $MAJOR creation failed!"
     exit 1
  fi
else
  echo "** os $OPERATINGSYSTEM $MAJOR ($OPERATINGSYSTEMID) exists"
fi

echo ""
HOSTGROUPID=`check_for_entity hostgroup $HOSTGROUP`
if [ -z "$HOSTGROUPID" ] ; then
  echo "** Creating hostgroup $HOSTGROUP"
  if [ `create_object hostgroup "hostgroup create --name $HOSTGROUP"` -eq 0 ] ; then
     HOSTGROUPID=`check_for_entity hostgroup $HOSTGROUP`
     echo -e "\t** Created hostgroup $HOSTGROUP ($HOSTGROUPID)"
  else
     echo "ERROR: hostgroup $HOSTGROUP creation failed!"
     exit 1
  fi
else
  echo "** hostgroup $HOSTGROUP ($HOSTGROUPID) exists"
fi

echo ""
HOSTNAMEID=`check_for_entity host $HOSTNAME`
if [ -z "$HOSTNAMEID" ] ; then
  echo "** Creating host $HOSTNAME"
  if [ `create_object host "host create --name $HOSTNAME"` -eq 0 ] ; then
     HOSTNAMEID=`check_for_entity host $HOSTNAME`
     echo -e "\t** Created host $HOSTNAME ($HOSTNAMEID)"
  else
     echo "ERROR: host $HOSTNAME creation failed!"
     exit 1
  fi
else
  echo "** hostgroup $HOSTNAME ($HOSTNAMEID) exists"
fi

## hammer host create --name scott --domain "localdomain" --medium "Fedora mirror" --partition-table "Kickstart default" --architecture x86_64 --operatingsystem "Fedora 23" --hostgroup "test-group" --mac "50:7b:9d:4d:f1:39" --build false --managed false --environment test_env2 --parameters "TEST=VALUE,TEST2=VALUE2"


echo ""
echo "** Done"
echo ""
