package com.processing.api.support;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Three-level orthogonal array. Nonproportional vectors yield every pair of levels over GF(3). */
public final class Pairwise {
    private static final int[][] COLUMNS = {
            {1, 0, 0}, {0, 1, 0}, {1, 1, 0}, {0, 0, 1}, {1, 2, 0},
            {1, 0, 1}, {1, 0, 2}, {0, 1, 1}, {0, 1, 2}, {1, 1, 1}
    };

    private Pairwise() { }

    public static List<int[]> rows(int factors) {
        if (factors < 2 || factors > COLUMNS.length) {
            throw new IllegalArgumentException("Expected 2 to " + COLUMNS.length + " factors");
        }
        List<int[]> result = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            int[] row = new int[factors];
            for (int j = 0; j < factors; j++) {
                int[] vector = COLUMNS[j];
                row[j] = (vector[0] * (i / 9) + vector[1] * (i / 3 % 3) + vector[2] * (i % 3)) % 3;
            }
            result.add(row);
        }
        for (int first = 0; first < factors; first++) {
            for (int second = first + 1; second < factors; second++) {
                var combinations = new HashSet<Integer>();
                for (int[] row : result) {
                    combinations.add(row[first] * 3 + row[second]);
                }
                assertEquals(9, combinations.size(), "factor pair " + first + "/" + second);
            }
        }
        return result;
    }
}
