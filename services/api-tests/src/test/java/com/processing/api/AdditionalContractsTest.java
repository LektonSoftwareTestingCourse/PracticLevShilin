package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/** Sources: BinIssuerController, CardController, ChartsFilter and Gateway route configuration. */
class AdditionalContractsTest {
    @Test
    void binMappingLifecycle() {
        // given: CMS BIN namespace is private to CMS, outside the preseeded 400000–400004 range.
        String bin = "98" + Data.digits(4);
        var request = Map.of("bin", bin, "issuerId", "P5TEST");
        // when
        var response = Api.post("cards", "/api/bins", request);
        // then
        response.then().statusCode(201).body("bin", equalTo(bin)).body("issuerId", equalTo("P5TEST"));
        try {
            given().spec(Api.spec("cards")).get("/api/bins/{bin}", bin).then().statusCode(200)
                    .body("bin", equalTo(bin)).body("issuerId", equalTo("P5TEST"));
            given().spec(Api.spec("cards")).get("/api/bins").then().statusCode(200)
                    .body("findAll { it.bin == '" + bin + "' }.issuerId", contains("P5TEST"));
            Api.error(Api.post("cards", "/api/bins", request), 409, "card-management", bin);
        } finally {
            given().spec(Api.spec("cards")).delete("/api/bins/{bin}", bin)
                    .then().statusCode(204).body(isEmptyString());
        }
        Api.error(given().spec(Api.spec("cards")).get("/api/bins/{bin}", bin), 404, "card-management", bin);
    }

    @ParameterizedTest
    @CsvSource({"limit,0,400,limit", "offset,-1,400,offset", "limit,10001,413,10000",
            "bin,12345,400,6 digits", "issuerId,lowercase,400,issuerId"})
    void invalidCardFilters(String field, String value, int status, String message) {
        // given / when
        var response = given().spec(Api.spec("gateway")).queryParam(field, value).get("/api/cards");
        // then
        Api.error(response, status, "card-management", message);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bulkUpdateRequiresExactlyOneSelector(boolean both) {
        // given
        Map<String, Object> request = both
                ? Map.of("bins", List.of("980000"), "pans", List.of("98" + Data.digits(14)), "status", "ACTIVE")
                : Map.of("status", "ACTIVE");
        // when
        var response = Api.post("gateway", "/api/cards/bulk-update", request);
        // then
        Api.error(response, 400, "card-management", both ? "Only one" : "Either");
    }

    @ParameterizedTest
    @CsvSource({"POST,start", "GET,status"})
    void legacyTerminalRoutes(String method, String suffix) {
        // given: OpenAPI lists these paths, while TerminalSimulatorController does not map them (D10).
        String path = "/api/simulator/terminal/" + suffix;
        // when
        var response = method.equals("POST")
                ? Api.post("terminal", path, Map.of())
                : given().spec(Api.spec("terminal")).get(path);
        // then: Terminal's catch-all advice also catches missing Spring MVC resources.
        Api.error(response, 500, "terminal-simulator", suffix);
    }

    @Test
    @Tag("fault")
    void gatewayHealthReportsUnavailableDependency() {
        // given / when: the extra Gateway is configured with an unreachable Switch.
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                .untilAsserted(() -> {
                    var response = given().spec(Api.spec("unavailable")).get("/health");
                    // then
                    response.then().statusCode(503).body("service", equalTo("gateway"))
                            .body("status", equalTo("degraded")).body("services.switch", equalTo("unavailable"))
                            .body("services.logger", equalTo("ok"));
                });
    }
}
