package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Strict expected contracts. Run explicitly: -Dgroups=defect -DexcludedGroups=fault. See report D01-D07. */
@Tag("defect")
class KnownDefectsTest {
    @Test
    void authorizationResponseMtiMustBe0110() {
        // given: docs/api-spec.md AuthorizationResponse, ISO 8583 response MTI.
        var card = Api.card();
        // when
        var response = Api.post("gateway", "/api/transactions", Data.authorization(card.get("pan").toString()));
        // then
        response.then().statusCode(200).body("status", equalTo("APPROVED")).body("mti", equalTo("0110"));
    }

    @Test
    void deleteInvalidatesCachedCard() {
        // given: GET after DELETE cannot return the previously active card (cache coherence).
        var card = Api.card();
        String pan = card.get("pan").toString();
        given().spec(Api.spec("gateway")).get("/api/cards/{pan}", pan).then().statusCode(200)
                .body("status", equalTo("ACTIVE"));
        // when
        given().spec(Api.spec("gateway")).delete("/api/cards/{pan}", pan).then().statusCode(204).body(isEmptyString());
        // then
        given().spec(Api.spec("gateway")).get("/api/cards/{pan}", pan).then().statusCode(404)
                .body("error", equalTo("CardNotFoundException"));
    }

    @Test
    void createMustRejectInconsistentLimits() {
        // given: Card.withData rejects daily > monthly, same invariant should hold on creation.
        var request = Data.card();
        request.put("dailyLimit", 500001);
        // when
        var response = Api.post("gateway", "/api/cards", request);
        // then
        Api.error(response, 400, "card-management", "Daily limit");
    }

    @Test
    void cardValidationMustIdentifyField() {
        // given: practice-5-TZ requires the field and violated rule in the error.
        var request = Data.card();
        request.put("currencyCode", "64");
        // when
        var response = Api.post("gateway", "/api/cards", request);
        // then
        Api.error(response, 400, "card-management", "currencyCode");
        response.then().body("message", containsString("exactly 3"));
    }

    @Test
    void merchantListMustBeRoutedByGateway() {
        // given / when: docs/api/openapi.yaml /api/simulator/merchants.
        var response = given().spec(Api.spec("gateway")).get("/api/simulator/merchants");
        // then
        response.then().statusCode(200).body("size()", greaterThan(0));
    }

    @Test
    void rollbackMustNotCreditMoreThanReserved() {
        // given: reserve/rollback conservation of funds.
        var card = Api.card();
        String rrn = Data.digits(12);
        String path = "/api/cards/" + card.get("pan");
        Api.post("gateway", path + "/reserve", Map.of("rrn", rrn, "amount", 100))
                .then().statusCode(200).body("availableBalance", equalTo(999900));
        // when
        var response = Api.post("gateway", path + "/rollback", Map.of("pan", card.get("pan"), "rrn", rrn, "amount", 101));
        // then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"));
        given().spec(Api.spec("cards")).get(path).then().statusCode(200).body("availableBalance", equalTo(999900));
    }

    @Test
    void reversedDatesMustExplainTheRule() {
        // given / when: DateRangeValidator rejects reversed bounds; handler drops object-level errors.
        var response = given().spec(Api.spec("gateway")).queryParam("dateFrom", "2026-02-02")
                .queryParam("dateTo", "2026-02-01").get("/api/transactions/search");
        // then
        Api.error(response, 400, "transaction-logger", "dateFrom");
        response.then().body("message", containsString("dateTo"));
    }
}
