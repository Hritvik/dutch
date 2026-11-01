package com.dutch.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GatewayRoutePropertiesTest {

    @Test
    void supplies_local_defaults_when_unset() {
        GatewayRouteProperties properties = new GatewayRouteProperties(null, null);
        assertThat(properties.umsBaseUrl()).isEqualTo("http://localhost:8082");
        assertThat(properties.ledgerBaseUrl()).isEqualTo("http://localhost:8083");
    }

    @Test
    void strips_a_trailing_slash() {
        GatewayRouteProperties properties = new GatewayRouteProperties(
                "https://ums.example.test/", "https://ledger.example.test/");
        assertThat(properties.umsBaseUrl()).isEqualTo("https://ums.example.test");
        assertThat(properties.ledgerBaseUrl()).isEqualTo("https://ledger.example.test");
    }

    @Test
    void rejects_a_non_http_scheme() {
        assertThatThrownBy(() -> new GatewayRouteProperties("ftp://ums.example.test", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_query_or_fragment() {
        assertThatThrownBy(() -> new GatewayRouteProperties("https://ums.example.test?x=1", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatewayRouteProperties("https://ums.example.test#top", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
