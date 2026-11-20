package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static org.hamcrest.Matchers.*;

/** Source: common AuthorizationRequest/RollbackRequest. D08: default Spring error hides validation details. */
class InternalValidationTest {
    static Stream<Arguments> invalidAuthorization() {
        List<Arguments> cases = new ArrayList<>();
        Map<String, Integer> lengths = Map.of("mti", 4, "stan", 6, "pan", 16, "processingCode", 6,
                "currencyCode", 3, "terminalId", 8, "merchantId", 15, "mcc", 4);
        lengths.forEach((field, length) -> {
            for (Object value : new Object[] {null, "", " ", "1".repeat(length - 1), "1".repeat(length + 1)}) {
                cases.add(Arguments.of(field, value));
            }
        });
        for (String field : List.of("acquirerId", "issuerId")) {
            for (Object value : new Object[] {null, "", " "}) {
                cases.add(Arguments.of(field, value));
            }
        }
        cases.add(Arguments.of("amount", null));
        cases.add(Arguments.of("amount", -1));
        cases.add(Arguments.of("transmissionDateTime", null));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidAuthorization")
    void authorizeValidation(String field, Object value) {
        // given
        var request = Data.authorization("400000" + Data.digits(10));
        request.put(field, value);
        // when
        var response = Api.post("auth", "/api/internal/authorize", request);
        // then: service ownership by direct endpoint + path; field details are a recorded defect.
        response.then().statusCode(400).body("status", equalTo(400)).body("error", equalTo("Bad Request"))
                .body("path", equalTo("/api/internal/authorize")).body("timestamp", notNullValue());
    }

    static Stream<Arguments> invalidRollback() {
        List<Arguments> cases = new ArrayList<>();
        for (String field : List.of("pan", "rrn")) {
            int length = field.equals("pan") ? 16 : 12;
            for (Object value : new Object[] {null, "", "1".repeat(length - 1), "1".repeat(length + 1),
                    "A".repeat(length)}) {
                cases.add(Arguments.of(field, value));
            }
        }
        for (Object value : new Object[] {null, 0, -1}) {
            cases.add(Arguments.of("amount", value));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidRollback")
    void rollbackValidation(String field, Object value) {
        // given
        var request = new java.util.LinkedHashMap<String, Object>(
                Map.of("pan", "400000" + Data.digits(10), "rrn", Data.digits(12), "amount", 1));
        request.put(field, value);
        // when
        var response = Api.post("auth", "/api/internal/rollback", request);
        // then
        response.then().statusCode(400).body("status", equalTo(400)).body("error", equalTo("Bad Request"))
                .body("path", equalTo("/api/internal/rollback")).body("timestamp", notNullValue());
    }
}
