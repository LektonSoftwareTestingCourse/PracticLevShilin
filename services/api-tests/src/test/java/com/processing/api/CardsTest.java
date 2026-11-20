package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Sources: CardController, CardServiceImpl, Card/Reservation domain objects and DTO validators. */
class CardsTest {
    @Test
    void createReadPatchCacheAndDelete() {
        // given
        var request = Data.card();
        var created = Api.post("gateway", "/api/cards", request).then().statusCode(201)
                .body("cardholderName", equalTo(request.get("cardholderName"))).body("bin", equalTo("400000"))
                .body("currencyCode", equalTo("643")).body("dailyLimit", equalTo(100000))
                .body("monthlyLimit", equalTo(500000)).body("availableBalance", equalTo(1000000))
                .body("id", matchesPattern("[0-9a-f-]{36}")).body("createdAt", notNullValue())
                .extract().response();
        String pan = created.path("pan");
        given().spec(Api.spec("gateway")).get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("pan", equalTo(pan)).header("X-Cache", equalTo("MISS"));
        given().spec(Api.spec("gateway")).get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("pan", equalTo(pan)).header("X-Cache", equalTo("HIT"));
        // when
        var response = given().spec(Api.spec("gateway")).body(Map.of("availableBalance", 12345))
                .patch("/api/cards/{pan}", pan);
        // then
        response.then().statusCode(200).body("availableBalance", equalTo(12345));
        given().spec(Api.spec("gateway")).get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("availableBalance", equalTo(12345)).header("X-Cache", equalTo("MISS"));
        given().spec(Api.spec("gateway")).delete("/api/cards/{pan}", pan)
                .then().statusCode(204).body(isEmptyString());
        // D05: cached GET survives DELETE 204; strict regression lives in KnownDefectsTest.
    }

    @Test
    void reservationDuplicateRollbackAndConflict() {
        // given
        var card = Api.card();
        String path = "/api/cards/" + card.get("pan");
        String rrn = Data.digits(12);
        var reservation = Map.of("amount", 100, "rrn", rrn);
        // when
        var response = Api.post("gateway", path + "/reserve", reservation);
        // then
        response.then().statusCode(200).body("availableBalance", equalTo(999900));
        Api.error(Api.post("gateway", path + "/reserve", reservation), 409, "card-management", "");
        // Reservation.rolledBack closes the whole reservation; partial multi-step rollback is not supported.
        Api.post("gateway", path + "/rollback", Map.of("pan", card.get("pan"), "amount", 100, "rrn", rrn))
                .then().statusCode(200).body("availableBalance", equalTo(1000000));
        Api.error(Api.post("gateway", path + "/rollback",
                Map.of("pan", card.get("pan"), "amount", 1, "rrn", rrn)), 409, "card-management", "");
    }

    @Test
    void insufficientFundsAndUnknownReservation() {
        // given
        var card = Api.card();
        String path = "/api/cards/" + card.get("pan");
        // when
        var reserve = Api.post("gateway", path + "/reserve", Map.of("amount", 1000001, "rrn", Data.digits(12)));
        // then
        Api.error(reserve, 402, "card-management", "");
        reserve.then().body("error", equalTo("InsufficientFundsException"));
        Api.error(Api.post("gateway", path + "/rollback",
                Map.of("pan", card.get("pan"), "amount", 1, "rrn", Data.digits(12))), 404, "card-management", "");
        given().spec(Api.spec("cards")).get(path).then().statusCode(200).body("availableBalance", equalTo(1000000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"40000", "4000000", "ABCDEF"})
    void invalidBin(String bin) {
        // given
        var request = Data.card();
        request.put("bin", bin);
        // when
        var response = Api.post("gateway", "/api/cards", request);
        // then
        Api.error(response, 400, "card-management", "BIN number must contain exactly 6 digits");
    }

    static Stream<Arguments> invalidCards() {
        List<Arguments> cases = new ArrayList<>();
        for (String field : List.of("bin", "cardholderName", "currencyCode")) {
            cases.add(Arguments.of(field, null, "must not be blank"));
        }
        cases.add(Arguments.of("cardholderName", "John", "Cardholder name"));
        cases.add(Arguments.of("cardholderName", "IVAN1", "Cardholder name"));
        cases.add(Arguments.of("currencyCode", "64", "exactly 3"));
        cases.add(Arguments.of("currencyCode", "6430", "exactly 3"));
        cases.add(Arguments.of("currencyCode", "ABC", "digits only"));
        cases.add(Arguments.of("dailyLimit", -1, "negative"));
        cases.add(Arguments.of("monthlyLimit", -1, "negative"));
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("invalidCards")
    void invalidCreateField(String field, Object value, String message) {
        // given
        var request = Data.card();
        request.put(field, value);
        // when
        var response = Api.post("gateway", "/api/cards", request);
        // then: D03 CMS validation messages omit the field name; check the rule and origin explicitly.
        Api.error(response, 400, "card-management", message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "400000123456789", "40000012345678901", "AAAAAAAAAAAAAAAA"})
    void invalidPanForReadPatchDelete(String pan) {
        // given / when
        var read = given().spec(Api.spec("gateway")).get("/api/cards/{pan}", pan);
        var patch = given().spec(Api.spec("gateway")).body(Map.of("status", "ACTIVE"))
                .patch("/api/cards/{pan}", pan);
        var delete = given().spec(Api.spec("gateway")).delete("/api/cards/{pan}", pan);
        // then
        for (var response : List.of(read, patch, delete)) {
            Api.error(response, 400, "card-management", "PAN number must contain exactly 16 digits");
        }
    }

    @Test
    void unknownCardForReadPatchDeleteReserveRollback() {
        // given
        String pan = "999999" + Data.digits(10);
        String path = "/api/cards/" + pan;
        // when
        var read = given().spec(Api.spec("gateway")).get(path);
        var patch = given().spec(Api.spec("gateway")).body(Map.of("status", "ACTIVE")).patch(path);
        var delete = given().spec(Api.spec("gateway")).delete(path);
        var reserve = Api.post("gateway", path + "/reserve", Map.of("amount", 1, "rrn", Data.digits(12)));
        var rollback = Api.post("gateway", path + "/rollback",
                Map.of("pan", pan, "amount", 1, "rrn", Data.digits(12)));
        // then
        for (var response : List.of(read, patch, delete, reserve, rollback)) {
            Api.error(response, 404, "card-management", "9999********" + pan.substring(12));
        }
    }

    @Test
    void generateAndBulkUpdateOnlyOwnCards() {
        // given
        var generated = Api.post("gateway", "/api/cards/generate", Map.of("count", 2, "bins", List.of("400000")))
                .then().statusCode(201).body("generated", equalTo(2)).body("cards", hasSize(2)).extract().response();
        List<String> pans = generated.path("cards.pan");
        // when
        var response = Api.post("gateway", "/api/cards/bulk-update", Map.of("pans", pans, "status", "BLOCKED"));
        // then
        response.then().statusCode(200).body("updated", equalTo(2));
        for (String pan : pans) {
            given().spec(Api.spec("cards")).get("/api/cards/{pan}", pan).then().statusCode(200)
                    .body("pan", equalTo(pan)).body("status", equalTo("BLOCKED"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void invalidGenerationCount(int count) {
        // given / when
        var response = Api.post("gateway", "/api/cards/generate", Map.of("count", count, "bins", List.of("400000")));
        // then
        Api.error(response, 400, "card-management", "greater than or equal to 1");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 10})
    void listCardsAndEmptyFilter(int limit) {
        // given
        Api.card();
        // when
        var response = given().spec(Api.spec("gateway")).queryParam("limit", limit).queryParam("offset", 0)
                .queryParam("bin", "400000").queryParam("issuerId", "ISS001").queryParam("status", "ACTIVE")
                .get("/api/cards");
        // then
        response.then().statusCode(200).body("total", greaterThanOrEqualTo(1))
                .body("cards.size()", allOf(greaterThanOrEqualTo(1), lessThanOrEqualTo(limit)))
                .body("cards.bin", everyItem(equalTo("400000"))).body("cards.status", everyItem(equalTo("ACTIVE")));
        given().spec(Api.spec("gateway")).queryParam("startDate", "2100-01-01T00:00:00Z")
                .queryParam("endDate", "2100-01-02T00:00:00Z").get("/api/cards").then().statusCode(200)
                .body("total", equalTo(0)).body("cards", hasSize(0));
    }
}
