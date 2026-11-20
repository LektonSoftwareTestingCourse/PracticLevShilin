package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Sources: TerminalSimulatorController, SimulationController and their request DTOs. */
class SimulatorTest {
    @ParameterizedTest
    @ValueSource(strings = {"normal", "mixed", "high_value", "night_time", "declines_test"})
    void terminalScenarios(String scenario) {
        // given
        var card = Api.card();
        if (List.of("mixed", "declines_test").contains(scenario)) {
            given().spec(Api.spec("gateway")).body(Map.of("status", "BLOCKED"))
                    .patch("/api/cards/{pan}", card.get("pan")).then().statusCode(200)
                    .body("status", equalTo("BLOCKED"));
            Api.card();
        }
        var request = Map.of("count", 1, "scenario", scenario, "tps", 10);
        // when
        var response = Api.post("gateway", "/api/simulator/terminal/run", request);
        // then
        response.then().statusCode(200).body("totalSubmitted", equalTo(1)).body("transactions", hasSize(1))
                .body("elapsedMs", greaterThanOrEqualTo(0))
                .body("transactions[0].status", oneOf("APPROVED", "DECLINED"));
        assertEquals(1, response.jsonPath().getInt("approved") + response.jsonPath().getInt("declined"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"grocery", "electronics", "restaurant", "travel"})
    void merchantScenarios(String scenario) {
        // given
        Api.card();
        var request = Map.of("count", 1, "scenario", scenario, "tps", 2);
        // when
        var response = Api.post("gateway", "/api/simulator/merchant/run", request);
        // then
        response.then().statusCode(200).body("totalSubmitted", equalTo(1)).body("transactions", hasSize(1))
                .body("elapsedMs", greaterThanOrEqualTo(0))
                .body("transactions[0].responseCode", matchesPattern("[0-9]{2}"));
        assertEquals(1, response.jsonPath().getInt("approved") + response.jsonPath().getInt("declined"));
    }

    @ParameterizedTest
    @CsvSource({"terminal,count,0", "terminal,count,-1", "terminal,tps,0", "terminal,tps,-1",
            "merchant,count,0", "merchant,count,-1", "merchant,tps,0", "merchant,tps,-1"})
    void invalidNumericFields(String service, String field, int value) {
        // given
        var request = new LinkedHashMap<String, Object>(Map.of("count", 1, "tps", 2,
                "scenario", service.equals("terminal") ? "normal" : "grocery"));
        request.put(field, value);
        // when
        var response = Api.post("gateway", "/api/simulator/" + service + "/run", request);
        // then
        Api.error(response, 400, service.equals("terminal") ? "terminal-simulator" : "Merchant acquirer simulator", field);
    }

    @Test
    void merchantUnknownMccAndMissingFee() {
        // given
        Api.card();
        // when
        var simulation = Api.post("gateway", "/api/simulator/merchant/run",
                Map.of("count", 1, "scenario", "grocery", "mccCodes", List.of("0000"), "tps", 2));
        var fee = Api.post("gateway", "/api/simulator/merchant/fee", Data.authorization("400000" + Data.digits(10)));
        // then
        Api.error(simulation, 404, "Merchant acquirer simulator", "");
        Api.error(fee, 404, "Merchant acquirer simulator", "");
        fee.then().body("error", equalTo("Resource not found"));
    }

    @Test
    void merchantReturnsCalculatedFeeForRecordedTransaction() {
        Api.card();
        Instant start = Instant.now().minusSeconds(1);
        var simulation = Api.post("gateway", "/api/simulator/merchant/run",
                Map.of("count", 1, "scenario", "grocery", "mccCodes", List.of("5411"), "tps", 2))
                .then().statusCode(200).body("transactions", hasSize(1)).extract().response();
        String stan = simulation.path("transactions[0].stan");
        String rrn = simulation.path("transactions[0].rrn");
        List<Map<String, Object>> merchants = given().spec(Api.spec("merchant")).get("/api/simulator/merchants")
                .then().statusCode(200).extract().jsonPath().getList("");
        var selected = new AtomicReference<Map<String, Object>>();
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            List<Map<String, Object>> recent = given().spec(Api.spec("gateway")).queryParam("limit", 100)
                    .get("/api/dashboard/recent").then().statusCode(200).extract().jsonPath().getList("");
            var matches = recent.stream().filter(row -> stan.equals(row.get("stan")))
                    .filter(row -> java.util.Objects.equals(rrn, row.get("rrn")))
                    .filter(row -> Instant.parse(row.get("createdAt").toString()).isAfter(start))
                    .filter(row -> merchants.stream().anyMatch(merchant -> merchant.get("id").equals(row.get("merchantId"))))
                    .toList();
            assertEquals(1, matches.size(), "record matching the simulated transaction");
            selected.set(matches.getFirst());
        });
        var row = selected.get();
        var merchant = merchants.stream().filter(item -> item.get("id").equals(row.get("merchantId")))
                .findFirst().orElseThrow();
        var key = Map.of("pan", row.get("pan"), "stan", stan, "terminalId", row.get("terminalId"),
                "amount", row.get("amount"), "transmissionDateTime", row.get("transmissionDateTime"));
        var expected = new BigDecimal(row.get("amount").toString())
                .multiply(new BigDecimal(merchant.get("acquiringFee").toString())).setScale(0, RoundingMode.HALF_EVEN);
        var result = Api.post("gateway", "/api/simulator/merchant/fee", key).then().statusCode(200)
                .body("acquirerFee", notNullValue()).extract().response();
        assertEquals(0, expected.compareTo(new BigDecimal(result.path("acquirerFee").toString())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pan", "stan", "terminalId", "transmissionDateTime"})
    void merchantFeeRequiresEveryLookupField(String field) {
        var key = Data.authorization("400000" + Data.digits(10));
        key.remove(field);
        Api.error(Api.post("gateway", "/api/simulator/merchant/fee", key),
                400, "Merchant acquirer simulator", field);
    }

    @Test
    void continuousStartDuplicateAndStop() {
        // given
        Api.card();
        var request = Map.of("tps", 1, "transactionType", "NORMAL");
        // when
        var started = Api.post("gateway", "/api/simulator/terminal/start-continuous", request);
        try {
            // then
            started.then().statusCode(200).body(isEmptyString());
            Api.error(Api.post("gateway", "/api/simulator/terminal/start-continuous", request),
                    422, "terminal-simulator", "already running");
        } finally {
            // Always stop only the simulation started by this test.
            if (started.statusCode() == 200) {
                Api.post("gateway", "/api/simulator/terminal/stop", Map.of()).then()
                        .statusCode(200).body(isEmptyString());
            }
        }
    }

    @Test
    void merchantListIsOnlyAvailableDirectly() {
        // given / when
        var direct = given().spec(Api.spec("merchant")).get("/api/simulator/merchants");
        var gateway = given().spec(Api.spec("gateway")).get("/api/simulator/merchants");
        // then: D04 missing Gateway route, independently checked against direct endpoint.
        direct.then().statusCode(200).body("size()", greaterThan(0)).body("mcc", everyItem(notNullValue()));
        gateway.then().statusCode(404).body("status", equalTo(404)).body("path", equalTo("/api/simulator/merchants"));
    }
}
