#!/bin/sh

export MY_IP_ADDR=$(hostname -I)
if [ -z "$RAGENT_SERVICE_HOST" ]; then
    if [ -n "$ADMIN_SERVICE_HOST" ]; then
        export RAGENT_SERVICE_HOST=$ADMIN_SERVICE_HOST
        export RAGENT_SERVICE_PORT=$ADMIN_SERVICE_PORT_RAGENT
    else
        echo "ERROR: router agent service not configured";
        export RAGENT_SERVICE_HOST=localhost;
        export RAGENT_SERVICE_PORT=55672;
    fi
fi
if [ -z "$LINK_CAPACITY" ]; then
    export LINK_CAPACITY=250
fi
if [ -z "$WORKER_THREADS" ]; then
    export WORKER_THREADS=4
fi
if [ -z "$AUTHENTICATION_SERVICE_SASL_INIT_HOST" ]; then
    export AUTHENTICATION_SERVICE_SASL_INIT_HOST=$AUTHENTICATION_SERVICE_HOST
    echo "WARNING: sasl-init hostname not configured, using $AUTHENTICATION_SERVICE_SASL_INIT_HOST"
fi
DOLLAR='$' envsubst < /etc/qpid-dispatch/qdrouterd.conf.template > /tmp/qdrouterd.conf
if [ -n "$TOPIC_NAME" ]; then
    export ADDRESS_NAME=$TOPIC_NAME;
fi
if [ -n "$AMQP_KAFKA_BRIDGE_SERVICE_HOST" ]; then
    envsubst < /etc/qpid-dispatch/amqp-kafka-bridge.snippet >> /tmp/qdrouterd.conf
fi

exec /sbin/qdrouterd --conf /tmp/qdrouterd.conf
