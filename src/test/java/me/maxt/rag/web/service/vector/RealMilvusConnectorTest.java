package me.maxt.rag.web.service.vector;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class RealMilvusConnectorTest {

    @Test
    void shouldProbeReachableWhenPortOpen() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            RealMilvusConnector connector = new RealMilvusConnector();
            assertThat(connector.reachable("localhost", server.getLocalPort(), 2000)).isTrue();
        }
    }

    @Test
    void shouldProbeUnreachableWhenPortClosed() throws Exception {
        int closedPort;
        try (ServerSocket server = new ServerSocket(0)) {
            closedPort = server.getLocalPort();
        }
        RealMilvusConnector connector = new RealMilvusConnector();
        assertThat(connector.reachable("localhost", closedPort, 2000)).isFalse();
    }
}
