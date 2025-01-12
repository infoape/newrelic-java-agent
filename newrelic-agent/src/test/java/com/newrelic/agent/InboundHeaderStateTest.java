/*
 *
 *  * Copyright 2020 New Relic Corporation. All rights reserved.
 *  * SPDX-License-Identifier: Apache-2.0
 *
 */

package com.newrelic.agent;

import com.newrelic.agent.bridge.TransactionNamePriority;
import com.newrelic.agent.config.AgentConfig;
import com.newrelic.agent.config.AgentConfigFactory;
import com.newrelic.agent.config.CrossProcessConfig;
import com.newrelic.agent.config.DistributedTracingConfig;
import com.newrelic.agent.dispatchers.Dispatcher;
import com.newrelic.agent.logging.AgentLogManager;
import com.newrelic.agent.logging.IAgentLogger;
import com.newrelic.agent.service.ServiceFactory;
import com.newrelic.agent.transaction.PriorityTransactionName;
import com.newrelic.api.agent.ExtendedRequest;
import com.newrelic.api.agent.HeaderType;
import com.newrelic.api.agent.InboundHeaders;
import com.newrelic.api.agent.OutboundHeaders;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.logging.Level;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.contains;

public class InboundHeaderStateTest {

    private final MockServiceManager serviceManager = new MockServiceManager();
    private final String encodingKey = getClass().getName();
    private InboundHeaderState ihs;
    private ExtendedRequest request;
    private CrossProcessConfig crossProcessConfig;
    private DistributedTracingConfig distributedTracingConfig;
    private AgentConfig agentConfig;
    private Transaction tx;
    private static IAgentLogger logger;
    static MockedStatic<AgentLogManager> agentLogManager;;

    @BeforeClass
    public static void setupBeforeClass() {
        logger = Mockito.mock(IAgentLogger.class);
        Mockito.when(logger.getChildLogger(Mockito.any(Class.class))).thenReturn(Mockito.mock(IAgentLogger.class));
        agentLogManager = Mockito.mockStatic(AgentLogManager.class);
        agentLogManager.when(AgentLogManager::getLogger).thenReturn(logger);
    }

    @AfterClass
    public static void teardown() {
        agentLogManager.close();
    }

    @Before
    public void setup() {
        serviceManager.setConfigService(new MockConfigService(AgentConfigFactory.createAgentConfig(
                Collections.emptyMap(), Collections.emptyMap(), null)));
        ServiceFactory.setServiceManager(serviceManager);

        OutboundHeaders outboundHeaders = mock(OutboundHeaders.class);
        InboundHeaders inboundHeaders = mock(InboundHeaders.class);
        request = mock(ExtendedRequest.class);
        agentConfig = mock(AgentConfig.class);
        crossProcessConfig = mock(CrossProcessConfig.class);
        distributedTracingConfig = mock(DistributedTracingConfig.class);
        tx = mock(Transaction.class);

        when(inboundHeaders.getHeaderType()).thenReturn(HeaderType.HTTP);
        when(outboundHeaders.getHeaderType()).thenReturn(HeaderType.HTTP);
        when(crossProcessConfig.getEncodingKey()).thenReturn(encodingKey);
        when(crossProcessConfig.isCrossApplicationTracing()).thenReturn(true);
        when(distributedTracingConfig.isEnabled()).thenReturn(false);
        when(agentConfig.getDistributedTracingConfig()).thenReturn(distributedTracingConfig);
        when(tx.isIgnore()).thenReturn(false);
        when(tx.getDispatcher()).thenReturn(mock(Dispatcher.class));
        when(tx.getDispatcher().getRequest()).thenReturn(request);
        when(tx.getDispatcher().getRequest().getHeaderType()).thenReturn(HeaderType.HTTP);
        when(tx.getCrossProcessConfig()).thenReturn(crossProcessConfig);
        when(tx.getAgentConfig()).thenReturn(agentConfig);
        when(tx.getPriorityTransactionName()).thenReturn(
                PriorityTransactionName.create("Test", "TEST", TransactionNamePriority.NONE));
        when(tx.getApplicationName()).thenReturn("TestApp");
        when(tx.getLock()).thenReturn(new Object());
    }

    @Test
    public void testNullHeaders() throws UnsupportedEncodingException {
        ihs = new InboundHeaderState(tx, null);
        assertNull(ihs.getClientCrossProcessId());
        assertNull(ihs.getInboundTripId());
        assertNull(ihs.getReferrerGuid());
        assertNull(ihs.getReferringPathHash());
        assertEquals(-1L, ihs.getRequestContentLength());
    }

    @Test
    public void testNoNRHeaders() {
        when(request.getHeader("Content-Length")).thenReturn("42");

        assertFalse(distributedTracingConfig.isEnabled());
        assertTrue(crossProcessConfig.isCrossApplicationTracing());

        ihs = new InboundHeaderState(tx, request);
        assertNull(ihs.getClientCrossProcessId());
        assertNull(ihs.getInboundTripId());
        assertNull(ihs.getReferrerGuid());
        assertNull(ihs.getReferringPathHash());
        assertEquals(42L, ihs.getRequestContentLength());
    }

    @Test
    public void testSyntheticsHeadersInvalid() {
        when(request.getHeader("X-NewRelic-Synthetics")).thenReturn("{'key':true}");
        when(request.getHeader("NewRelicSynthetics")).thenReturn("{'key':true}");

        ihs = new InboundHeaderState(tx, request);

        assertNull(ihs.getSyntheticsResourceId());
    }

    @Test
    public void testSyntheticsHeadersErrors() {
        doSyntheticsTest(Level.FINEST, "Could not determine synthetics version",
                "NotANumber", "",false);
        doSyntheticsTest(Level.FINE, "Synthetic transaction tracing failed: invalid version",
                "2", "",false);
        doSyntheticsTest(Level.FINE, "Synthetic transaction tracing failed: while parsing header",
                "1", "NotANumber", true);
    }

    @Test
    public void testSyntheticsHeadersSuccess() {
        String resourceId = "fd09bfa1-bd85-4f8a-9bee-8d51582f5a54";
        String jobId = "77cbc5dc-327b-4542-90f0-335644134bed";
        String monitorId = "3e5c28ac-7cf3-4faf-ae52-ff36bc93504a";
        String value = "[\"-1000\",417446,\""+resourceId+"\",\""+jobId+"\",\""+monitorId+"\"]";
        when(request.getHeader("X-NewRelic-Synthetics")).thenReturn(value);
        when(request.getHeader("NewRelicSynthetics")).thenReturn(value);

        ihs = new InboundHeaderState(tx, request);

        assertEquals(HeadersUtil.SYNTHETICS_VERSION_NONE, ihs.getSyntheticsVersion());
        assertEquals(resourceId, ihs.getSyntheticsResourceId());
        assertEquals(jobId, ihs.getSyntheticsJobId());
        assertEquals(monitorId, ihs.getSyntheticsMonitorId());
    }

    private void doSyntheticsTest(Level expectedLevel, String expectedLogMsg,
            String version, String accountId, boolean thirdArg) {

        String value = "[\""+version+"\",\""+accountId+"\",\""+"\",\""+"\",\""+"\"]";
        when(request.getHeader("X-NewRelic-Synthetics")).thenReturn(value);
        when(request.getHeader("NewRelicSynthetics")).thenReturn(value);

        ihs = new InboundHeaderState(tx, request);

        if (thirdArg) {
            verify(logger).log(refEq(expectedLevel), contains(expectedLogMsg), any(Object.class), any(Object.class), any(Object.class));
        } else {
            verify(logger).log(refEq(expectedLevel), contains(expectedLogMsg), any(Object.class), any(Object.class));
        }
    }

    @Test
    public void testBadNRHeaders1() {
        when(request.getHeader("X-Foo")).thenReturn("FOO");
        when(request.getHeader(HeadersUtil.NEWRELIC_APP_DATA_HEADER)).thenReturn("FOO");

        assertFalse(distributedTracingConfig.isEnabled());
        assertTrue(crossProcessConfig.isCrossApplicationTracing());

        ihs = new InboundHeaderState(tx, request);
        assertNull(ihs.getClientCrossProcessId());
        assertNull(ihs.getInboundTripId());
        assertNull(ihs.getReferrerGuid());
        assertNull(ihs.getReferringPathHash());
        assertEquals(-1L, ihs.getRequestContentLength());
    }

    @Test
    public void testBadNRHeaders2() {
        when(request.getHeader("X-Bar")).thenReturn("BAR");
        when(request.getHeader(HeadersUtil.NEWRELIC_ID_HEADER)).thenReturn("BAR");

        assertFalse(distributedTracingConfig.isEnabled());
        assertTrue(crossProcessConfig.isCrossApplicationTracing());

        ihs = new InboundHeaderState(tx, request);
        assertNull(ihs.getClientCrossProcessId());
        assertNull(ihs.getInboundTripId());
        assertNull(ihs.getReferrerGuid());
        assertNull(ihs.getReferringPathHash());
        assertEquals(-1L, ihs.getRequestContentLength());
    }

    @Test
    public void testBadNRHeaders3() {
        when(request.getHeader("X-Baz")).thenReturn("BAZ");
        when(request.getHeader(HeadersUtil.NEWRELIC_TRANSACTION_HEADER)).thenReturn("BAZ");

        assertFalse(distributedTracingConfig.isEnabled());
        assertTrue(crossProcessConfig.isCrossApplicationTracing());

        ihs = new InboundHeaderState(tx, request);
        assertNull(ihs.getClientCrossProcessId());
        assertNull(ihs.getInboundTripId());
        assertNull(ihs.getReferrerGuid());
        assertNull(ihs.getReferringPathHash());
        assertEquals(-1L, ihs.getRequestContentLength());
    }

    @Test
    public void testBadNRHeaders4() {
        when(request.getHeader("X-Foo")).thenReturn("FOO");
        when(request.getHeader("X-Bar")).thenReturn("BAR");
        when(request.getHeader("X-Baz")).thenReturn("BAZ");
        when(request.getHeader("Content-Length")).thenReturn("One would expect a number here.");

        assertFalse(distributedTracingConfig.isEnabled());
        assertTrue(crossProcessConfig.isCrossApplicationTracing());

        ihs = new InboundHeaderState(tx, request);
        assertNull(ihs.getClientCrossProcessId());
        assertNull(ihs.getInboundTripId());
        assertNull(ihs.getReferrerGuid());
        assertNull(ihs.getReferringPathHash());
        assertEquals(-1L, ihs.getRequestContentLength());
    }

    @Test
    public void testBadNRHeaders5() {
        when(request.getHeader("X-Foo")).thenReturn("FOO");
        when(request.getHeader("X-Bar")).thenReturn("BAR");
        when(request.getHeader("X-Baz")).thenReturn("BAZ");

        when(request.getHeader(HeadersUtil.NEWRELIC_APP_DATA_HEADER)).thenReturn(
                "[\"6#66\",\"WebTransaction\\/test\\/test\",Nan,NotAFloat,NotAnInt,\"NotAHexString\"]");

        assertFalse(distributedTracingConfig.isEnabled());
        assertTrue(crossProcessConfig.isCrossApplicationTracing());

        ihs = new InboundHeaderState(tx, request);
        assertNull(ihs.getClientCrossProcessId());
        assertNull(ihs.getInboundTripId());
        assertNull(ihs.getReferrerGuid());
        assertNull(ihs.getReferringPathHash());
        assertEquals(-1L, ihs.getRequestContentLength());
    }

    @Test
    public void testBadNRHeaders6() {
        when(request.getHeader("X-Foo")).thenReturn("FOO");
        when(request.getHeader("X-Bar")).thenReturn("BAR");
        when(request.getHeader("X-Baz")).thenReturn("BAZ");

        when(request.getHeader(HeadersUtil.NEWRELIC_TRANSACTION_HEADER)).thenReturn(
                "[\"ee8e5ef1a374c0ec\",NotABoolean,\"ee8eZZZZZZc0ec\",\"8fZZZZ90\"]");

        assertFalse(distributedTracingConfig.isEnabled());
        assertTrue(crossProcessConfig.isCrossApplicationTracing());

        ihs = new InboundHeaderState(tx, request);
        assertNull(ihs.getClientCrossProcessId());
        assertNull(ihs.getInboundTripId());
        assertNull(ihs.getReferrerGuid());
        assertNull(ihs.getReferringPathHash());
        assertEquals(-1L, ihs.getRequestContentLength());
    }
}
