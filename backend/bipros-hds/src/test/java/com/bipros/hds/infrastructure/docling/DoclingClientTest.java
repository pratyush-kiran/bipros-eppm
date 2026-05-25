package com.bipros.hds.infrastructure.docling;

import com.bipros.hds.config.HdsProperties;
import com.bipros.hds.infrastructure.docling.dto.DoclingResponse;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DoclingClientTest {

    MockWebServer server;
    DoclingClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        HdsProperties props = new HdsProperties();
        props.getDocling().setUrl(server.url("/").toString().replaceAll("/$", ""));
        props.getDocling().setTimeoutMinutes(1);
        client = new DoclingClient(props);
    }

    @AfterEach
    void tearDown() throws Exception { server.shutdown(); }

    @Test
    void parsesResponse() {
        server.enqueue(new MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""
                {"status":"ok","pages":3,"blocks":[
                  {"type":"heading","level":1,"page":1,"text":"Vol 3"},
                  {"type":"paragraph","page":1,"text":"intro text"}
                ]}
                """));

        DoclingResponse resp = client.parse(new byte[]{1, 2, 3}, "test.pdf");

        assertThat(resp.getStatus()).isEqualTo("ok");
        assertThat(resp.getPages()).isEqualTo(3);
        assertThat(resp.getBlocks()).hasSize(2);
        assertThat(resp.getBlocks().get(0).getType()).isEqualTo("heading");
    }
}
