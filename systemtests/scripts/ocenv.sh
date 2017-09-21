#!/bin/sh
HOST=${1:-localhost}
NAMESPACE=${2-myproject}
USER=${3-developer}

export OPENSHIFT_USER=$USER
export OPENSHIFT_USE_TLS=true
export OPENSHIFT_USE_KEYCLOAK=true
export OPENSHIFT_MULTITENANT=true
export OPENSHIFT_KEYCLOAK_USER=admin
export OPENSHIFT_KEYCLOAK_PASSWORD=admin
export OPENSHIFT_TOKEN=`oc whoami -t`
export OPENSHIFT_MASTER_URL=https://${HOST}:8443
export OPENSHIFT_NAMESPACE=${NAMESPACE}
