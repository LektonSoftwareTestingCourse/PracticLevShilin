package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.Matchers.*;

/** Sources: AuthServiceImpl, AuthControllerImpl, DeclineOutcome; discrepancies D01/D02 in report. */
class AuthorizationTest {
    @ParameterizedTest
    @CsvSource({"gateway,/api/transactions", "switch,/api/internal/route", "auth,/api/internal/authorize"})
    void approvalAndBalance(String service, String path) {
        // given
        var card = Api.card();
        var request = Data.authorization(card.get("pan").toString());
        // when
        var response = Api.post(service, path, request);
        // then: D01, current implementation echoes request MTI.
        response.then().statusCode(200).body(matchesJsonSchemaInClasspath("schemas/authorization.json"))
                .body("mti", equalTo("0100")).body("stan", equalTo(request.get("stan")))
                .body("status", equalTo("APPROVED")).body("responseCode", equalTo("00"))
                .body("rrn", matchesPattern("[0-9]{12}"))
                .body("authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("declineReason", nullValue()).body("processingTimeMs", greaterThanOrEqualTo(0));
        given().spec(Api.spec("cards")).when().get("/api/cards/{pan}", card.get("pan"))
                .then().statusCode(200).body("availableBalance", equalTo(999900));
        if (!service.equals("auth")) {
            Api.stored(card.get("pan").toString(), request.get("stan").toString());
        }
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED,05,CARD_BLOCKED", "INACTIVE,05,CARD_INACTIVE", "EXPIRED,54,CARD_EXPIRED"})
    void stateDeclines(String state, String code, String reason) {
        // given
        var card = Api.card();
        given().spec(Api.spec("gateway")).body(Map.of("status", state))
                .patch("/api/cards/{pan}", card.get("pan")).then().statusCode(200).body("status", equalTo(state));
        var request = Data.authorization(card.get("pan").toString());
        // when
        var response = Api.post("auth", "/api/internal/authorize", request);
        // then
        response.then().statusCode(403).body("responseCode", equalTo(code)).body("declineReason", equalTo(reason))
                .body("status", equalTo("DECLINED")).body("authCode", nullValue()).body("rrn", nullValue());
        Api.post("gateway", "/api/transactions", request).then().statusCode(200)
                .body("responseCode", equalTo(code)).body("declineReason", equalTo(reason));
    }

    @Test
    void expiryReachedThroughRequestDate() {
        // given: expiry is immutable via API, move transmission date beyond the card's last valid day.
        var card = Api.card();
        var request = Data.authorization(card.get("pan").toString());
        String expiry = card.get("expiryDate").toString();
        YearMonth month = expiry.contains("-") ? YearMonth.parse(expiry)
                : YearMonth.parse(expiry, DateTimeFormatter.ofPattern("MMyy"));
        request.put("transmissionDateTime", month.plusMonths(1).atDay(1) + "T00:00:00Z");
        // when
        var response = Api.post("auth", "/api/internal/authorize", request);
        // then
        response.then().statusCode(403).body("responseCode", equalTo("54"))
                .body("declineReason", equalTo("CARD_EXPIRED")).body("status", equalTo("DECLINED"));
    }

    @ParameterizedTest
    @CsvSource({"availableBalance,99,422,51,INSUFFICIENT_FUNDS",
            "dailyLimit,99,400,61,EXCEEDS_AMOUNT_LIMIT", "monthlyLimit,99,400,61,EXCEEDS_AMOUNT_LIMIT"})
    void fundsAndLimits(String field, int value, int status, String code, String reason) {
        // given
        var card = Api.card();
        var patch = field.equals("monthlyLimit") ? Map.of(field, value, "dailyLimit", value) : Map.of(field, value);
        given().spec(Api.spec("gateway")).body(patch)
                .patch("/api/cards/{pan}", card.get("pan")).then().statusCode(200).body(field, equalTo(value));
        var request = Data.authorization(card.get("pan").toString());
        // when
        var response = Api.post("auth", "/api/internal/authorize", request);
        // then
        response.then().statusCode(status).body("responseCode", equalTo(code))
                .body("declineReason", equalTo(reason)).body("status", equalTo("DECLINED"));
        Api.post("gateway", "/api/transactions", request).then().statusCode(200)
                .body("responseCode", equalTo(code)).body("declineReason", equalTo(reason));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dailyLimit", "monthlyLimit", "availableBalance"})
    void exactLimitIsAccepted(String field) {
        // given
        var card = Api.card();
        var patch = field.equals("monthlyLimit") ? Map.of(field, 100, "dailyLimit", 100) : Map.of(field, 100);
        given().spec(Api.spec("gateway")).body(patch)
                .patch("/api/cards/{pan}", card.get("pan")).then().statusCode(200).body(field, equalTo(100));
        // when
        var response = Api.post("auth", "/api/internal/authorize", Data.authorization(card.get("pan").toString()));
        // then
        response.then().statusCode(200).body("responseCode", equalTo("00")).body("status", equalTo("APPROVED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"400000", "999999"})
    void missingCardAndUnknownBin(String bin) {
        // given: different paths, known BIN -> CMS lookup; unknown BIN -> Switch routing rejection.
        var request = Data.authorization(bin + Data.digits(10));
        // when
        var response = Api.post("gateway", "/api/transactions", request);
        // then
        response.then().statusCode(200).body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND")).body("status", equalTo("DECLINED"));
        Api.post("auth", "/api/internal/authorize", request).then().statusCode(404)
                .body("responseCode", equalTo("14")).body("declineReason", equalTo("CARD_NOT_FOUND"));
    }

    @Test
    void monthlyLimitIsSharedAcrossDifferentDays() {
        String pan = Api.card().get("pan").toString();
        given().spec(Api.spec("gateway")).body(Map.of("dailyLimit", 100, "monthlyLimit", 199))
                .patch("/api/cards/{pan}", pan).then().statusCode(200)
                .body("dailyLimit", equalTo(100)).body("monthlyLimit", equalTo(199));
        var first = Data.authorization(pan);
        var second = Data.authorization(pan);
        var boundary = Data.authorization(pan);
        var month = YearMonth.now(java.time.ZoneOffset.UTC).minusMonths(1);
        first.put("transmissionDateTime", month.atDay(1) + "T12:00:00Z");
        second.put("transmissionDateTime", month.atDay(2) + "T12:00:00Z");
        boundary.put("transmissionDateTime", month.atDay(2) + "T12:01:00Z");
        boundary.put("amount", 99);
        Api.post("auth", "/api/internal/authorize", first).then().statusCode(200).body("responseCode", equalTo("00"));
        Api.post("auth", "/api/internal/authorize", second).then().statusCode(400)
                .body("responseCode", equalTo("61")).body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
        Api.post("auth", "/api/internal/authorize", boundary).then().statusCode(200).body("responseCode", equalTo("00"));
        given().spec(Api.spec("cards")).get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("availableBalance", equalTo(999801));
    }

    @Test
    void rollbackRestoresBalanceAndRepeatedRollbackConflicts() {
        // given
        var card = Api.card();
        var approved = Api.post("auth", "/api/internal/authorize", Data.authorization(card.get("pan").toString()))
                .then().statusCode(200).body("status", equalTo("APPROVED")).extract().response();
        var rollback = Map.of("pan", card.get("pan"), "amount", 100, "rrn", approved.path("rrn"));
        // when
        var response = Api.post("auth", "/api/internal/rollback", rollback);
        // then
        response.then().statusCode(200).body("responseCode", equalTo("00"))
                .body("status", equalTo("APPROVED")).body("rrn", equalTo(approved.path("rrn")));
        given().spec(Api.spec("cards")).get("/api/cards/{pan}", card.get("pan"))
                .then().statusCode(200).body("availableBalance", equalTo(1000000));
        Api.post("auth", "/api/internal/rollback", rollback).then().statusCode(409)
                .body("responseCode", equalTo("05")).body("declineReason", equalTo("ALREADY_ROLLED_BACK"));
    }

    @Test
    void rollbackUnknownReservation() {
        // given
        var card = Api.card();
        var request = Map.of("pan", card.get("pan"), "amount", 100, "rrn", Data.digits(12));
        // when
        var response = Api.post("auth", "/api/internal/rollback", request);
        // then
        response.then().statusCode(404).body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }
}
