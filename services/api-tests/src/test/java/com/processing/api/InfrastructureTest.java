package com.processing.api;

import com.processing.api.support.Api;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Sources: each HealthController, Gateway routes, BinLookupController and NotificationController. */
class InfrastructureTest {
    @ParameterizedTest
    @CsvSource({"gateway,gateway", "cards,card-management", "switch,switch", "auth,authorization",
            "logger,transaction-logger", "terminal,terminal-simulator", "merchant,merchant-acquirer"})
    void serviceHealth(String service, String name) {
        // given / when
        var response = given().spec(Api.spec(service)).get("/health");
        // then
        response.then().statusCode(200).body("status", equalTo("ok"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bin", "notification"})
    void actuatorHealth(String service) {
        // given / when
        var response = given().spec(Api.spec(service)).get("/actuator/health");
        // then
        response.then().statusCode(200).body("status", equalTo("UP"));
    }

    @ParameterizedTest
    @CsvSource({"400000,ISS001", "400001,ISS002", "400002,ISS003", "400003,ISS004", "400004,ISS005"})
    void binLookup(String bin, String issuer) {
        // given / when
        var response = given().spec(Api.spec("bin")).queryParam("delay", 1).get("/api/bin/{bin}", bin);
        // then
        response.then().statusCode(200).body("bin", equalTo(bin)).body("issuerId", equalTo(issuer))
                .body("issuerName", startsWith("Test Bank "));
    }

    @Test
    void unknownBinAndExplicitFailure() {
        // given / when
        var unknown = given().spec(Api.spec("bin")).get("/api/bin/999999");
        var failure = given().spec(Api.spec("bin")).queryParam("fail", true).get("/api/bin/400000");
        // then: intentionally empty responses in BinLookupController.
        unknown.then().statusCode(404).body(isEmptyString());
        failure.then().statusCode(500).body(isEmptyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"switch", "logger", "terminal", "merchant", "cards"})
    void aggregatedOpenApi(String name) {
        // given / when
        var response = given().spec(Api.spec("gateway")).get("/" + name + "-docs");
        // then
        response.then().statusCode(200).body("openapi", startsWith("3."))
                .body("paths.size()", greaterThan(0));
    }

    @Test
    void notificationsPagination() {
        // given / when
        var response = given().spec(Api.spec("notification")).queryParam("page", 0).queryParam("size", 1)
                .get("/api/notifications");
        // then
        response.then().statusCode(200).body("number", equalTo(0)).body("size", equalTo(1))
                .body("content.size()", lessThanOrEqualTo(1)).body("totalElements", greaterThanOrEqualTo(0));
    }
}
