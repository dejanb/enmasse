/*
 * Copyright 2016 Red Hat Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package enmasse.mqtt;

import enmasse.mqtt.endpoints.AmqpPublishEndpoint;
import enmasse.mqtt.endpoints.AmqpPublisher;
import enmasse.mqtt.endpoints.AmqpReceiverEndpoint;
import enmasse.mqtt.endpoints.AmqpSubscriptionServiceEndpoint;
import enmasse.mqtt.endpoints.AmqpWillServiceEndpoint;
import enmasse.mqtt.messages.AmqpPublishMessage;
import enmasse.mqtt.messages.AmqpPubrelMessage;
import enmasse.mqtt.messages.AmqpSessionMessage;
import enmasse.mqtt.messages.AmqpSessionPresentMessage;
import enmasse.mqtt.messages.AmqpSubscribeMessage;
import enmasse.mqtt.messages.AmqpTopicSubscription;
import enmasse.mqtt.messages.AmqpUnsubscribeMessage;
import enmasse.mqtt.messages.AmqpWillClearMessage;
import enmasse.mqtt.messages.AmqpWillMessage;
import io.netty.handler.codec.mqtt.MqttConnectReturnCode;
import io.netty.handler.codec.mqtt.MqttQoS;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.mqtt.MqttEndpoint;
import io.vertx.mqtt.MqttWill;
import io.vertx.mqtt.messages.MqttPublishMessage;
import io.vertx.mqtt.messages.MqttSubscribeMessage;
import io.vertx.mqtt.messages.MqttUnsubscribeMessage;
import io.vertx.proton.ProtonClient;
import io.vertx.proton.ProtonClientOptions;
import io.vertx.proton.ProtonConnection;
import io.vertx.proton.ProtonDelivery;
import io.vertx.proton.ProtonLinkOptions;
import io.vertx.proton.ProtonReceiver;
import io.vertx.proton.ProtonSender;
import org.apache.qpid.proton.amqp.messaging.Accepted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AMQP bridging class from/to the MQTT endpoint to/from the AMQP related endpoints
 */
public class AmqpBridge {

    private static final int AMQP_SERVICES_CONNECTION_TIMEOUT = 5000; // in ms

    private static final Logger LOG = LoggerFactory.getLogger(AmqpBridge.class);

    private Vertx vertx;

    private ProtonClient client;
    private ProtonConnection connection;

    // local endpoint for handling remote connected MQTT client
    private MqttEndpoint mqttEndpoint;

    // endpoint for handling communication with Will Service (WS)
    private AmqpWillServiceEndpoint wsEndpoint;
    // endpoint for handling communication with Subscription Service (SS)
    private AmqpSubscriptionServiceEndpoint ssEndpoint;
    // endpoint for handling incoming messages on the unique client address
    private AmqpReceiverEndpoint rcvEndpoint;
    // endpoint for publishing message on topic (via AMQP)
    private AmqpPublishEndpoint pubEndpoint;

    /**
     * Constructor
     *
     * @param vertx Vert.x instance
     * @param mqttEndpoint  MQTT local endpoint
     */
    public AmqpBridge(Vertx vertx, MqttEndpoint mqttEndpoint) {
        this.vertx = vertx;
        this.mqttEndpoint = mqttEndpoint;
    }

    /**
     * Open the bridge and connect to the AMQP service provider
     *
     * @param address   AMQP service provider address
     * @param port      AMQP service provider port
     * @param openHandler   handler called when the open is completed (with success or not)
     */
    public void open(String address, int port, Handler<AsyncResult<AmqpBridge>> openHandler) {

        this.client = ProtonClient.create(this.vertx);

        // TODO: check correlation between MQTT and AMQP keep alive
        ProtonClientOptions clientOptions = new ProtonClientOptions();
        clientOptions.setHeartbeat(this.mqttEndpoint.keepAliveTimeSeconds() * 1000);

        this.client.connect(clientOptions, address, port, done -> {

            if (done.succeeded()) {

                this.connection = done.result();
                this.connection.open();

                // setup MQTT endpoint handlers and AMQP endpoints
                this.setupMqttEndpoint();
                this.setupAmqpEndpoits();

                // setup a Future for completed connection steps with all services
                // with AMQP_WILL and AMQP_SESSION/AMQP_SESSION_PRESENT handled
                Future<AmqpSessionPresentMessage> connectionFuture = Future.future();
                connectionFuture.setHandler(ar -> {

                    if (ar.succeeded()) {

                        this.mqttEndpoint.accept(ar.result().isSessionPresent());
                        LOG.info("Connection accepted");

                        openHandler.handle(Future.succeededFuture(AmqpBridge.this));

                    } else {

                        this.mqttEndpoint.reject(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE);
                        LOG.error("Connection NOT accepted");

                        openHandler.handle(Future.failedFuture(ar.cause()));
                    }

                    LOG.info("CONNACK to MQTT client {}", this.mqttEndpoint.clientIdentifier());
                });

                // step 1 : send AMQP_WILL to Will Service
                Future<ProtonDelivery> willFuture = Future.future();
                // if remote MQTT has specified the will
                if (this.mqttEndpoint.will().isWillFlag()) {

                    // sending AMQP_WILL
                    MqttWill will = this.mqttEndpoint.will();

                    AmqpWillMessage amqpWillMessage =
                            new AmqpWillMessage(will.isWillRetain(),
                                    will.willTopic(),
                                    MqttQoS.valueOf(will.willQos()),
                                    Buffer.buffer(will.willMessage()));

                    this.wsEndpoint.sendWill(amqpWillMessage, willFuture.completer());
                } else {

                    // otherwise just complete the Future
                    willFuture.complete();
                }

                willFuture.compose(v -> {

                    // handling AMQP_SESSION_PRESENT reply from Subscription Service
                    this.rcvEndpoint.sessionHandler(amqpSessionPresentMessage -> {

                        LOG.info("Session present: {}", amqpSessionPresentMessage.isSessionPresent());

                        this.rcvEndpoint.publishHandler(this::publishHandler);
                        this.rcvEndpoint.pubrelHandler(this::pubrelHandler);

                        connectionFuture.complete(amqpSessionPresentMessage);
                    });

                    // step 2 : send AMQP_SESSION to Subscription Service
                    Future<ProtonDelivery> cleanSessionFuture = Future.future();

                    // sending AMQP_SESSION
                    AmqpSessionMessage amqpSessionMessage =
                            new AmqpSessionMessage(this.mqttEndpoint.isCleanSession(),
                                    this.mqttEndpoint.clientIdentifier());

                    this.ssEndpoint.sendCleanSession(amqpSessionMessage, cleanSessionFuture.completer());
                    return cleanSessionFuture;

                }).compose(v -> {
                    // nothing here !??
                }, connectionFuture);

                // timeout for the overall connection process
                vertx.setTimer(AMQP_SERVICES_CONNECTION_TIMEOUT, timer -> {
                   if (!connectionFuture.isComplete()) {
                       connectionFuture.fail("Timeout on connecting to AMQP services");
                   }
                });

            } else {

                LOG.error("Error connecting to AMQP services ...", done.cause());
                // no connection with the AMQP side
                this.mqttEndpoint.reject(MqttConnectReturnCode.CONNECTION_REFUSED_SERVER_UNAVAILABLE);

                openHandler.handle(Future.failedFuture(done.cause()));

                LOG.info("CONNACK to MQTT client {}", this.mqttEndpoint.clientIdentifier());
            }

        });

    }

    /**
     * Close the bridge with all related attached links and connection to AMQP services
     */
    public void close() {

        this.wsEndpoint.close();
        this.ssEndpoint.close();
        this.rcvEndpoint.close();
        this.pubEndpoint.close();

        this.connection.close();
    }

    /**
     * Handler for incoming MQTT PUBLISH message
     *
     * @param publish   PUBLISH message
     */
    private void publishHandler(MqttPublishMessage publish) {

        LOG.info("PUBLISH [{}] from MQTT client {}", publish.messageId(), this.mqttEndpoint.clientIdentifier());

        // TODO: simple way, without considering wildcards

        // check if a publisher already exists for the requested topic
        if (!this.pubEndpoint.isPublisher(publish.topicName())) {

            // create two sender for publishing QoS 0/1 and QoS 2 messages
            ProtonSender senderQoS01 = this.connection.createSender(publish.topicName());
            ProtonSender senderQoS2 = this.connection.createSender(publish.topicName());

            this.pubEndpoint.addPublisher(publish.topicName(), new AmqpPublisher(senderQoS01, senderQoS2));
        }

        // sending AMQP_PUBLISH
        AmqpPublishMessage amqpPublishMessage =
                new AmqpPublishMessage(publish.messageId(),
                        publish.qosLevel(),
                        publish.isDup(),
                        publish.isRetain(),
                        publish.topicName(),
                        publish.payload());

        pubEndpoint.publish(amqpPublishMessage, done -> {

            if (done.succeeded()) {

                ProtonDelivery delivery = done.result();
                if (delivery != null) {

                    if (publish.qosLevel() == MqttQoS.AT_LEAST_ONCE) {

                        this.mqttEndpoint.publishAcknowledge((int) amqpPublishMessage.messageId());
                        LOG.info("PUBACK [{}] to MQTT client {}", amqpPublishMessage.messageId(), this.mqttEndpoint.clientIdentifier());
                    } else {

                        this.mqttEndpoint.publishReceived((int) amqpPublishMessage.messageId());
                        LOG.info("PUBREC [{}] to MQTT client {}", amqpPublishMessage.messageId(), this.mqttEndpoint.clientIdentifier());
                    }

                }
            }

        });
    }

    /**
     * Handler for incoming AMQP_PUBLISH message
     *
     * @param publish   AMQP_PUBLISH message
     */
    private void publishHandler(AmqpPublishMessage publish) {

        this.mqttEndpoint.publish(publish.topic(), publish.payload(), publish.qos(), publish.isDup(), publish.isRetain());

        LOG.info("PUBLISH [{}] to MQTT client {}", publish.messageId(), this.mqttEndpoint.clientIdentifier());
    }

    /**
     * Handler for incoming AMQP_PUBREL message
     *
     * @param pubrel    AMQP_PUBREL message
     */
    private void pubrelHandler(AmqpPubrelMessage pubrel) {

        this.mqttEndpoint.publishRelease((int) pubrel.messageId());

        LOG.info("PUBREL [{}] to MQTT client {}", pubrel.messageId(), this.mqttEndpoint.clientIdentifier());
    }

    /**
     * Handler for incoming MQTT SUBSCRIBE message
     *
     * @param subscribe SUBSCRIBE message
     */
    private void subscribeHandler(MqttSubscribeMessage subscribe) {

        LOG.info("SUBSCRIBE [{}] from MQTT client {}", subscribe.messageId(), this.mqttEndpoint.clientIdentifier());

        // sending AMQP_SUBSCRIBE

        List<AmqpTopicSubscription> topicSubscriptions =
                subscribe.topicSubscriptions().stream().map(topicSubscription -> {
                    return new AmqpTopicSubscription(topicSubscription.topicName(), topicSubscription.qualityOfService());
                }).collect(Collectors.toList());

        AmqpSubscribeMessage amqpSubscribeMessage =
                new AmqpSubscribeMessage(this.mqttEndpoint.clientIdentifier(),
                        subscribe.messageId(),
                        topicSubscriptions);

        this.ssEndpoint.sendSubscribe(amqpSubscribeMessage, done -> {

            if (done.succeeded()) {

                ProtonDelivery delivery = done.result();

                List<Integer> grantedQoSLevels = null;
                if (delivery.getRemoteState() == Accepted.getInstance()) {

                    // QoS levels requested are granted
                    grantedQoSLevels = amqpSubscribeMessage.topicSubscriptions().stream().map(topicSubscription -> {
                        return topicSubscription.qos().value();
                    }).collect(Collectors.toList());

                } else {

                    // failure for all QoS levels requested
                    grantedQoSLevels = new ArrayList<>(Collections.nCopies(amqpSubscribeMessage.topicSubscriptions().size(), MqttQoS.FAILURE.value()));
                }

                this.mqttEndpoint.subscribeAcknowledge((int) amqpSubscribeMessage.messageId(), grantedQoSLevels);

                LOG.info("SUBACK [{}] to MQTT client {}", amqpSubscribeMessage.messageId(), this.mqttEndpoint.clientIdentifier());
            }
        });
    }

    /**
     * Handler for incoming MQTT UNSUBSCRIBE message
     *
     * @param unsubscribe   UNSUBSCRIBE message
     */
    private void unsubscribeHandler(MqttUnsubscribeMessage unsubscribe) {

        LOG.info("UNSUBSCRIBE [{}] from MQTT client {}", unsubscribe.messageId(), this.mqttEndpoint.clientIdentifier());

        // sending AMQP_UNSUBSCRIBE

        AmqpUnsubscribeMessage amqpUnsubscribeMessage =
                new AmqpUnsubscribeMessage(this.mqttEndpoint.clientIdentifier(),
                        unsubscribe.messageId(),
                        unsubscribe.topics());

        this.ssEndpoint.sendUnsubscribe(amqpUnsubscribeMessage, done -> {

            if (done.succeeded()) {

                this.mqttEndpoint.unsubscribeAcknowledge((int) amqpUnsubscribeMessage.messageId());

                LOG.info("UNSUBACK [{}] to MQTT client {}", amqpUnsubscribeMessage.messageId(), this.mqttEndpoint.clientIdentifier());
            }
        });
    }

    /**
     * Handler for incoming MQTT DISCONNECT message
     *
     * @param v
     */
    private void disconnectHandler(Void v) {

        LOG.info("DISCONNECT from MQTT client {}", this.mqttEndpoint.clientIdentifier());

        // sending AMQP_WILL_CLEAR
        AmqpWillClearMessage amqpWillClearMessage = new AmqpWillClearMessage();
        this.wsEndpoint.clearWill(amqpWillClearMessage, ar -> {

            this.wsEndpoint.close();
        });
    }

    /**
     * Handler for connection closed by remote MQTT client
     *
     * @param v
     */
    private void closeHandler(Void v) {

        this.wsEndpoint.close();
    }

    /**
     * Handler for incoming MQTT PUBACK message
     *
     * @param messageId message identifier
     */
    private void pubackHandler(int messageId) {

        LOG.info("PUBACK [{}] from MQTT client {}", messageId, this.mqttEndpoint.clientIdentifier());

        // a PUBLISH message with QoS 1 was sent to remote MQTT client (not settled yet at source)
        // now PUBACK is received so it's time to settle
        this.rcvEndpoint.settle(messageId);
    }

    /**
     * Handler for incoming MQTT PUBREL message
     *
     * @param messageId message identifier
     */
    private void pubrelHandler(int messageId) {

        LOG.info("PUBREL [{}] from MQTT client {}", messageId, this.mqttEndpoint.clientIdentifier());

        // a PUBLISH message with QoS 2 was received from remote MQTT client, PUBREC was already sent
        // as reply, now that PUBREL is coming it's time to settle and reply with PUBCOMP
        this.pubEndpoint.settle(messageId);

        this.mqttEndpoint.publishComplete(messageId);

        LOG.info("PUBCOMP [{}] to MQTT client {}", messageId, this.mqttEndpoint.clientIdentifier());
    }

    /**
     * Handler for incoming MQTT PUBREC message
     *
     * @param messageId message identifier
     */
    private void pubrecHandler(int messageId) {

        LOG.info("PUBREC [{}] from MQTT client {}", messageId, this.mqttEndpoint.clientIdentifier());

        AmqpPubrelMessage amqpPubrelMessage = new AmqpPubrelMessage(messageId);

        this.pubEndpoint.publish(amqpPubrelMessage, done -> {

            if (done.succeeded()) {

                this.rcvEndpoint.settle(messageId);
            }
        });
    }

    /**
     * Handler for incoming MQTT PUBCOMP message
     *
     * @param messageId message identifier
     */
    private void pubcompHandler(int messageId) {

        LOG.info("PUBCOMP [{}] from MQTT client {}", messageId, this.mqttEndpoint.clientIdentifier());

        // a PUBLISH message with QoS 2 was sent to remote MQTT client (not settled yet at source)
        // then PUBREC was received. The corresponding PUBREL was sent (after PUBLISH settlement at source)
        // and now the PUBCOMP was received so it's time to settle
        this.rcvEndpoint.settle(messageId);
    }

    /**
     * Setup handlers for MQTT endpoint
     */
    private void setupMqttEndpoint() {

        this.mqttEndpoint
                .publishHandler(this::publishHandler)
                .publishAcknowledgeHandler(this::pubackHandler)
                .publishReleaseHandler(this::pubrelHandler)
                .publishReceivedHandler(this::pubrecHandler)
                .publishCompleteHandler(this::pubcompHandler)
                .subscribeHandler(this::subscribeHandler)
                .unsubscribeHandler(this::unsubscribeHandler)
                .disconnectHandler(this::disconnectHandler)
                .closeHandler(this::closeHandler);
    }

    /**
     * Setup all AMQP endpoints
     */
    private void setupAmqpEndpoits() {

        // specified link name for the Will Service as MQTT clientid
        ProtonLinkOptions linkOptions = new ProtonLinkOptions();
        linkOptions.setLinkName(this.mqttEndpoint.clientIdentifier());

        // setup and open AMQP endpoint for receiving on unique client address
        ProtonReceiver rcvReceiver = this.connection.createReceiver(String.format(AmqpReceiverEndpoint.CLIENT_ENDPOINT_TEMPLATE, this.mqttEndpoint.clientIdentifier()));
        this.rcvEndpoint = new AmqpReceiverEndpoint(rcvReceiver);

        // setup and open AMQP endpoints to Will and Subscription services
        ProtonSender wsSender = this.connection.createSender(AmqpWillServiceEndpoint.WILL_SERVICE_ENDPOINT, linkOptions);
        this.wsEndpoint = new AmqpWillServiceEndpoint(wsSender);

        ProtonSender ssSender = this.connection.createSender(AmqpSubscriptionServiceEndpoint.SUBSCRIPTION_SERVICE_ENDPOINT);
        this.ssEndpoint = new AmqpSubscriptionServiceEndpoint(ssSender);

        // setup and open AMQP endpoint for publishing
        ProtonSender senderPubrel = this.connection.createSender(String.format(AmqpPublishEndpoint.AMQP_CLIENT_PUBREL_ENDPOINT_TEMPLATE, this.mqttEndpoint.clientIdentifier()));
        this.pubEndpoint = new AmqpPublishEndpoint(senderPubrel);

        this.rcvEndpoint.open();
        this.wsEndpoint.open();
        this.ssEndpoint.open();
        this.pubEndpoint.open();
    }
}
