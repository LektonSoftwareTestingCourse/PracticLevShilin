package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

/** Sources: InternalTransactionController, TransactionService, TransactionFilter and TransactionRequest. */
class LoggerTest {
    @Test
    void loggerDistinguishesMalformedRequestAndUnknownMethodOrRoute() {
        var malformed = Api.post("logger", "/api/internal/log", "{");
        Api.error(malformed, 400, "transaction-logger", "Request body is malformed");
        malformed.then().body("error", equalTo("Invalid request body"));
        var wrongMethod = given().spec(Api.spec("logger")).get("/api/internal/log");
        Api.error(wrongMethod, 405, "transaction-logger", "GET");
        var missingRoute = given().spec(Api.spec("logger")).get("/api/internal/" + UUID.randomUUID());
        Api.error(missingRoute, 404, "transaction-logger", "Resource not found");
    }

    @Test
    void unsupportedMediaTypeReachesLoggerFallbackHandler() {
        var response = given().spec(Api.spec("logger")).contentType("application/xml").body("<transaction/>")
                .post("/api/internal/log");
        // D14: MVC's unsupported media type is caught by the generic handler and rendered as 500.
        Api.error(response, 500, "transaction-logger", "Unexpected transaction logger error");
        response.then().body("error", equalTo("Internal server error")).body("retryAfterMs", equalTo("0"));
    }

    @Test
    void duplicateDeliveryIsIdempotent() {
        // given
        var request = Data.transaction();
        Api.post("logger", "/api/internal/log", request).then().statusCode(201)
                .body("id", equalTo(request.get("id"))).body("status", equalTo("stored"));
        // when
        var duplicate = Api.post("logger", "/api/internal/log", request);
        // then
        duplicate.then().statusCode(200).body("id", equalTo(request.get("id")))
                .body("amount", equalTo(100)).body("stan", equalTo(request.get("stan")))
                .body("status", equalTo("APPROVED"));
        Api.search(request.get("pan").toString()).then().statusCode(200)
                .body("total", equalTo(1)).body("transactions", hasSize(1))
                .body("transactions[0].id", equalTo(request.get("id")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "stan", "pan", "status", "merchantId", "rrn", "declineReason"})
    void sameKeyDifferentPayloadConflicts(String field) {
        // given
        var original = Data.transaction();
        Api.post("logger", "/api/internal/log", original).then().statusCode(201)
                .body("id", equalTo(original.get("id")));
        var changed = new LinkedHashMap<>(original);
        changed.put(field, switch (field) {
            case "amount" -> 101;
            case "stan" -> original.get(field).equals("000000") ? "000001" : "000000";
            case "pan" -> "999999" + Data.digits(10);
            case "status" -> "DECLINED";
            case "rrn" -> "999999999999";
            case "merchantId" -> "OTHER";
            default -> "INSUFFICIENT_FUNDS";
        });
        // when
        var response = Api.post("logger", "/api/internal/log", changed);
        // then
        Api.error(response, 409, "transaction-logger", original.get("id").toString());
        response.then().body("error", equalTo("Transaction conflict"));
        Api.search(original.get("pan").toString()).then().statusCode(200)
                .body("transactions", hasSize(1)).body("transactions[0].amount", equalTo(100))
                .body("transactions[0].status", equalTo("APPROVED"));
    }

    static Stream<Arguments> invalidBody() {
        List<Arguments> cases = new ArrayList<>();
        for (String field : List.of("id", "amount", "status", "transmissionDateTime", "createdAt")) {
            cases.add(Arguments.of(field, null));
        }
        for (String field : List.of("mti", "stan", "pan", "processingCode", "currencyCode", "terminalId",
                "merchantId", "mcc", "acquirerId")) {
            cases.add(Arguments.of(field, null));
            cases.add(Arguments.of(field, ""));
            cases.add(Arguments.of(field, " "));
        }
        Map<String, Integer> lengths = Map.of("mti", 4, "rrn", 12, "terminalId", 8, "terminalType", 10,
                "merchantId", 15, "acquirerId", 10, "issuerId", 10, "declineReason", 100, "authCode", 6);
        lengths.forEach((field, length) -> cases.add(Arguments.of(field, "A".repeat(length + 1))));
        Map<String, Integer> exact = Map.of("stan", 6, "pan", 16, "processingCode", 6, "currencyCode", 3, "mcc", 4);
        exact.forEach((field, length) -> {
            cases.add(Arguments.of(field, "1".repeat(length - 1)));
            cases.add(Arguments.of(field, "1".repeat(length + 1)));
            cases.add(Arguments.of(field, "A".repeat(length)));
        });
        cases.add(Arguments.of("amount", 0));
        cases.add(Arguments.of("amount", -1));
        cases.add(Arguments.of("acquiringFee", -1));
        cases.add(Arguments.of("processingTimeMs", -1));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidBody")
    void invalidTransaction(String field, Object value) {
        // given
        var request = Data.transaction();
        request.put(field, value);
        // when
        var response = Api.post("logger", "/api/internal/log", request);
        // then
        Api.error(response, 400, "transaction-logger", field + ":");
        response.then().body("error", equalTo("Validation error")).body("retryAfterMs", equalTo("0"));
    }

    @Test
    void rejectedRequestNeverAppears() {
        // given
        var request = Data.transaction();
        request.put("amount", -1);
        // when
        var response = Api.post("logger", "/api/internal/log", request);
        // then: during checks the whole observation window, not just the first empty response.
        Api.error(response, 400, "transaction-logger", "amount");
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                        Api.search(request.get("pan").toString()).then().statusCode(200)
                                .body("total", equalTo(0)).body("transactions", hasSize(0)));
    }

    @Test
    void allSearchFiltersAndPairwiseCombinations() {
        // given: all 8 filters, every singleton and every pair against one positive and one negative control.
        var target = Data.transaction();
        target.put("status", "DECLINED");
        target.put("declineReason", "INSUFFICIENT_FUNDS");
        var control = Data.transaction();
        control.put("pan", target.get("pan"));
        control.put("createdAt", "2020-01-01T12:00:00Z");
        control.put("transmissionDateTime", "2020-01-01T12:00:00Z");
        control.put("issuerId", "ISS002");
        control.put("mcc", "5812");
        for (var request : List.of(target, control)) {
            Api.post("logger", "/api/internal/log", request).then().statusCode(201)
                    .body("id", equalTo(request.get("id")));
        }
        String today = target.get("transmissionDateTime").toString().substring(0, 10);
        Map<String, Object> filters = Map.of("pan", target.get("pan"), "status", "DECLINED",
                "merchantId", target.get("merchantId"), "issuerId", "ISS001", "mcc", "5411",
                "declineReason", "INSUFFICIENT_FUNDS", "dateFrom", today, "dateTo", today);
        var keys = new ArrayList<>(filters.keySet());
        for (int first = 0; first < keys.size(); first++) {
            for (int second = first; second < keys.size(); second++) {
                var query = new LinkedHashMap<String, Object>();
                query.put("pan", target.get("pan"));
                query.put(keys.get(first), filters.get(keys.get(first)));
                query.put(keys.get(second), filters.get(keys.get(second)));
                // when
                var response = given().spec(Api.spec("gateway")).queryParams(query)
                        .when().get("/api/transactions/search");
                // then
                response.then().statusCode(200)
                        .body("transactions.findAll { it.id == '" + target.get("id") + "' }", hasSize(1));
                boolean excludesControl = query.keySet().stream().anyMatch(key -> !List.of("pan", "dateTo").contains(key));
                response.then().body("transactions", hasSize(excludesControl ? 1 : 2));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 50, 500})
    void paginationAndEmptySelection(int limit) {
        // given
        var first = Data.transaction();
        var second = new LinkedHashMap<>(first);
        second.put("id", UUID.randomUUID().toString());
        second.put("createdAt", "2020-01-01T00:00:00Z");
        for (var request : List.of(first, second)) {
            Api.post("logger", "/api/internal/log", request).then().statusCode(201)
                    .body("status", equalTo("stored"));
        }
        // when
        var response = given().spec(Api.spec("gateway")).queryParam("pan", first.get("pan"))
                .queryParam("limit", limit).queryParam("offset", 1).get("/api/transactions/search");
        // then
        response.then().statusCode(200).body("total", equalTo(2)).body("transactions", hasSize(1))
                .body("transactions[0].id", equalTo(second.get("id")));
        given().spec(Api.spec("gateway")).queryParam("pan", first.get("pan")).queryParam("offset", 2)
                .get("/api/transactions/search").then().statusCode(200)
                .body("total", equalTo(2)).body("transactions", hasSize(0));
        Api.search("999999" + Data.digits(10)).then().statusCode(200)
                .body("total", equalTo(0)).body("transactions", hasSize(0));
    }

    static Stream<Arguments> invalidQuery() {
        return Stream.of(Arguments.of("limit", "0"), Arguments.of("limit", "-1"), Arguments.of("limit", "501"),
                Arguments.of("offset", "-1"), Arguments.of("status", "UNKNOWN"),
                Arguments.of("dateFrom", "invalid"), Arguments.of("dateTo", "invalid"));
    }

    @ParameterizedTest
    @MethodSource("invalidQuery")
    void invalidFiltersApplyToSearchAndExport(String field, String value) {
        // given
        for (String path : List.of("search", "export")) {
            // when
            var response = given().spec(Api.spec("gateway")).queryParam(field, value)
                    .get("/api/transactions/" + path);
            // then
            Api.error(response, 400, "transaction-logger", field);
        }
    }

    @Test
    void exportContainsOnlyMatchingRows() {
        // given
        var request = Data.transaction();
        Api.post("logger", "/api/internal/log", request).then().statusCode(201).body("status", equalTo("stored"));
        // when
        var response = given().spec(Api.spec("gateway")).accept("text/csv").queryParam("pan", request.get("pan"))
                .get("/api/transactions/export");
        // then
        response.then().statusCode(200).contentType("text/csv")
                .header("Content-Disposition", equalTo("attachment; filename=\"transactions.csv\""))
                .body(containsString(request.get("id").toString())).body(containsString(request.get("stan").toString()));
        org.junit.jupiter.api.Assertions.assertEquals(2, response.asString().lines().count());
    }
}
