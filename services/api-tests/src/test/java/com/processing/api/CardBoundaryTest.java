package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import com.processing.api.support.Pairwise;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Boundary checks for CMS DTOs, Card.withData and reservation handling. */
class CardBoundaryTest {
    @ParameterizedTest
    @CsvSource({"dailyLimit,-1,Value can not be negative", "monthlyLimit,-1,Value can not be negative",
            "dailyLimit,500001,Daily limit", "monthlyLimit,99999,Daily limit"})
    void invalidPatchDoesNotChangeStoredLimits(String field, int value, String message) {
        String pan = Api.card().get("pan").toString();
        var response = given().spec(Api.spec("gateway")).body(Map.of(field, value)).patch("/api/cards/{pan}", pan);
        Api.error(response, 400, "card-management", message);
        given().spec(Api.spec("cards")).get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("dailyLimit", equalTo(100000)).body("monthlyLimit", equalTo(500000));
    }

    @Test
    void zeroLimitsAndEqualityAreAccepted() {
        String pan = Api.card().get("pan").toString();
        given().spec(Api.spec("gateway")).body(Map.of("dailyLimit", 0, "monthlyLimit", 0))
                .patch("/api/cards/{pan}", pan).then().statusCode(200)
                .body("dailyLimit", equalTo(0)).body("monthlyLimit", equalTo(0));
    }

    @ParameterizedTest
    @CsvSource({"reserve,-1", "reserve,0", "rollback,-1", "rollback,0"})
    void nonPositiveAmountsAreRejected(String route, int amount) {
        String pan = Api.card().get("pan").toString();
        var request = Map.of("pan", pan, "rrn", Data.digits(12), "amount", amount);
        Api.error(Api.post("gateway", "/api/cards/" + pan + "/" + route, request),
                400, "card-management", "must be greater than 0");
        given().spec(Api.spec("cards")).get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("availableBalance", equalTo(1000000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678901", "1234567890123", "AAAAAAAAAAAA"})
    void malformedRrnIsRejected(String rrn) {
        String pan = Api.card().get("pan").toString();
        for (String route : List.of("reserve", "rollback")) {
            Api.error(Api.post("gateway", "/api/cards/" + pan + "/" + route, Map.of("pan", pan, "rrn", rrn, "amount", 1)),
                    400, "card-management", "12 digits");
        }
    }

    @Test
    void reserveAndRollbackAcceptMinimumUnit() {
        String pan = Api.card().get("pan").toString();
        var request = Map.of("pan", pan, "rrn", Data.digits(12), "amount", 1);
        Api.post("gateway", "/api/cards/" + pan + "/reserve", request).then().statusCode(200)
                .body("availableBalance", equalTo(999999));
        Api.post("gateway", "/api/cards/" + pan + "/rollback", request).then().statusCode(200)
                .body("availableBalance", equalTo(1000000));
    }

    @Test
    void reserveRejectsBlockedCardWithoutTakingFunds() {
        String pan = Api.card().get("pan").toString();
        given().spec(Api.spec("gateway")).body(Map.of("status", "BLOCKED")).patch("/api/cards/{pan}", pan)
                .then().statusCode(200).body("status", equalTo("BLOCKED"));
        Api.error(Api.post("gateway", "/api/cards/" + pan + "/reserve", Map.of("rrn", Data.digits(12), "amount", 1)),
                409, "card-management", "BLOCKED");
        given().spec(Api.spec("cards")).get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("availableBalance", equalTo(1000000));
    }

    @Test
    void rollbackRejectsMissingAmount() {
        String pan = Api.card().get("pan").toString();
        Api.error(Api.post("gateway", "/api/cards/" + pan + "/rollback", Map.of("pan", pan, "rrn", Data.digits(12))),
                400, "card-management", "must not be null");
    }

    @Test
    void generatorEnforcesMaximumAndAcceptsMinimum() {
        Api.error(Api.post("gateway", "/api/cards/generate", Map.of("count", 10001, "bins", List.of("400000"))),
                400, "card-management", "Count exceeds maximum value: 10000");
        Api.post("gateway", "/api/cards/generate", Map.of("count", 1, "bins", List.of("400000")))
                .then().statusCode(201).body("cards", hasSize(1)).body("cards[0].bin", equalTo("400000"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"40000", "4000000", "abcdef"})
    void generatorRejectsInvalidBin(String bin) {
        Api.error(Api.post("gateway", "/api/cards/generate", Map.of("count", 1, "bins", List.of(bin))),
                400, "card-management", "6 digits");
    }

    @Test
    void generatorRejectsEmptyBinsAndUnknownBin() {
        Api.error(Api.post("gateway", "/api/cards/generate", Map.of("count", 1, "bins", List.of())),
                400, "card-management", "must not be empty");
        Api.error(Api.post("gateway", "/api/cards/generate", Map.of("count", 1, "bins", List.of("999999"))),
                404, "card-management", "999999");
    }

    @Test
    void cardListFiltersAndPagingCoverEveryPairOfClasses() {
        var card = Api.card();
        String pan = card.get("pan").toString();
        Instant created = Instant.parse(card.get("createdAt").toString());
        var options = List.of(
                List.of("status", "ACTIVE", "BLOCKED"), List.of("bin", "400000", "999999"),
                List.of("issuerId", card.get("issuerId").toString(), "ISS999"),
                List.of("startDate", created.minusSeconds(1).toString(), created.plusSeconds(1).toString()),
                List.of("endDate", created.plusSeconds(1).toString(), created.minusSeconds(1).toString()),
                List.of("limit", "1", "10000"), List.of("offset", "1", Integer.toString(Integer.MAX_VALUE)));
        for (int[] row : Pairwise.rows(options.size())) {
            var query = new LinkedHashMap<String, String>();
            for (int i = 0; i < row.length; i++) {
                if (row[i] != 0) {
                    query.put(options.get(i).getFirst(), options.get(i).get(row[i]));
                }
            }
            int limit = Integer.parseInt(query.getOrDefault("limit", "10"));
            var result = given().spec(Api.spec("gateway")).queryParams(query).get("/api/cards");
            result.then().statusCode(200).body("total", greaterThanOrEqualTo(0))
                    .body("cards", hasSize(lessThanOrEqualTo(limit)));
            if (java.util.Arrays.stream(row, 0, 5).anyMatch(level -> level == 2)) {
                result.then().body("cards.pan", not(hasItem(pan)));
            } else if (row[5] == 2 && row[6] == 0) {
                result.then().body("cards.pan", hasItem(pan));
            }
            if (row[6] == 2) {
                result.then().body("cards", hasSize(0));
            }
            for (String field : List.of("status", "bin", "issuerId")) {
                if (query.containsKey(field)) {
                    result.then().body("cards." + field, everyItem(equalTo(query.get(field))));
                }
            }
        }
    }
}
