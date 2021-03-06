/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.systemtest.standard.web;

import io.enmasse.systemtest.AddressType;
import io.enmasse.systemtest.Destination;
import io.enmasse.systemtest.KeycloakCredentials;
import io.enmasse.systemtest.ability.ITestBaseStandard;
import io.enmasse.systemtest.bases.web.WebConsoleTest;
import io.enmasse.systemtest.selenium.ISeleniumProviderChrome;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.enmasse.systemtest.TestTag.nonPR;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag(nonPR)
public class ChromeWebConsoleTest extends WebConsoleTest implements ITestBaseStandard, ISeleniumProviderChrome {

    @Test
    void testCreateDeleteQueue() throws Exception {
        doTestCreateDeleteAddress(Destination.queue("test-queue1", "pooled-queue"),
                Destination.queue("test-queue2", "sharded-queue"));
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testCreateDeleteTopic() throws Exception {
        doTestCreateDeleteAddress(Destination.topic("test-topic1", "pooled-topic"),
                Destination.topic("test-topic2", "sharded-topic"));
    }

    @Test
    void testCreateDeleteAnycast() throws Exception {
        doTestCreateDeleteAddress(Destination.anycast("test-anycast-chrome"));
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testCreateDeleteMulticast() throws Exception {
        doTestCreateDeleteAddress(Destination.multicast("test-multicast-chrome"));
    }

    @Test
    void testFilterAddressesByType() throws Exception {
        doTestFilterAddressesByType();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testFilterAddressesByName() throws Exception {
        doTestFilterAddressesByName();
    }

    @Test
    void testSortAddressesByName() throws Exception {
        doTestSortAddressesByName();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testSortConnectionsBySenders() throws Exception {
        doTestSortConnectionsBySenders();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testSortConnectionsByReceivers() throws Exception {
        doTestSortConnectionsByReceivers();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testFilterConnectionsByEncrypted() throws Exception {
        doTestFilterConnectionsByEncrypted();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testFilterConnectionsByUser() throws Exception {
        doTestFilterConnectionsByUser();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testFilterConnectionsByHostname() throws Exception {
        doTestFilterConnectionsByHostname();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testSortConnectionsByHostname() throws Exception {
        doTestSortConnectionsByHostname();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testFilterConnectionsByContainerId() throws Exception {
        doTestFilterConnectionsByContainerId();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testSortConnectionsByContainerId() throws Exception {
        doTestSortConnectionsByContainerId();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testMessagesMetrics() throws Exception {
        doTestMessagesMetrics();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testClientsMetrics() throws Exception {
        doTestClientsMetrics();
    }

    @Test
    void testCannotCreateAddresses() throws Exception {
        doTestCannotCreateAddresses();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testCannotDeleteAddresses() throws Exception {
        doTestCannotDeleteAddresses();
    }

    @Test
    void testViewAddresses() throws Exception {
        doTestViewAddresses();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testViewConnections() throws Exception {
        doTestViewConnections();
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testViewAddressesWildcards() throws Exception {
        doTestViewAddressesWildcards();
    }

    @Test()
    void testCannotOpenConsolePage() {
        assertThrows(IllegalAccessException.class, () -> doTestCanOpenConsolePage(new KeycloakCredentials("pepa", "pepaPa555")));
    }

    @Test
    @Disabled("Only few chrome tests are enabled, rest functionality is covered by firefox")
    void testCanOpenConsolePage() throws Exception {
        doTestCanOpenConsolePage(defaultCredentials);
    }

    @Override
    public boolean skipDummyAddress() {
        return true;
    }
}
