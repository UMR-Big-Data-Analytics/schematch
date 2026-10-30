package de.uni_marburg.schematch.constraintScore;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.data.metadata.dependency.UniqueColumnCombination;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class PrimaryKeyTest {

    static Column X1 = new Column("X1", List.of("A", "A", "B", "B", "C"));
    static Column object_id = new Column("object_id", List.of("1", "1", "2", "3", "3"));
    static Column X2 = new Column("X2", List.of("true", "true", "false", "false", "true"));
    static Column o_attr = new Column("o_attr", List.of("test1", "test11", "test2", "t3", "t3"));
    static Column v_len = new Column("v_len", List.of("1234567891"));
    static Column x_len = new Column("x_len", List.of("1234567891"));
    Table table = new Table("Test", List.of("X1", "object_id", "X2", "o_attr"), List.of(X1, object_id, X2, o_attr), null, null);
    Table table2 = new Table("Test", List.of("X1"), List.of(v_len), null, null);
    Table table3 = new Table("Test", List.of("object_id", "X1", "x_len"), List.of(X2, X1, x_len), null, null);


    @Test
    void primaryKeyScoreTest() {
        UniqueColumnCombination ucc = new UniqueColumnCombination(List.of(object_id, o_attr));
        UniqueColumnCombination ucc2 = new UniqueColumnCombination(List.of(x_len, x_len));
        double score = ucc.getPrimaryKeyScore();
        double score2 = ucc2.getPrimaryKeyScore();
        Assertions.assertTrue(score >= 0.5 && score <= 1.0);
        Assertions.assertTrue(score2 <= 0.5);
    }


    @Test
    void cardinalityScoreTest() {
        UniqueColumnCombination ucc = new UniqueColumnCombination(List.of(object_id));
        UniqueColumnCombination ucc2 = new UniqueColumnCombination(List.of(o_attr, object_id));
        UniqueColumnCombination ucc3 = new UniqueColumnCombination(List.of(o_attr, object_id, X1, X2));
        UniqueColumnCombination ucc4 = new UniqueColumnCombination(List.of());
        double score = ucc.cardinalityScore();
        double score2 = ucc2.cardinalityScore();
        double score3 = ucc3.cardinalityScore();
        double score4 = ucc4.cardinalityScore();
        Assertions.assertEquals(1.0, score);
        Assertions.assertEquals(0.5, score2);
        Assertions.assertEquals(0.25, score3);
        Assertions.assertEquals(0.0, score4);
    }

    @Test
    void valueLengthScoreTest() {
        UniqueColumnCombination ucc = new UniqueColumnCombination(List.of(object_id, o_attr));
        UniqueColumnCombination ucc2 = new UniqueColumnCombination(List.of(v_len));
        Assertions.assertEquals(1.0, ucc.valueLengthScore());
        Assertions.assertEquals(0.5, ucc2.valueLengthScore());
    }

    @Test
    void positionScoreTest() {
        UniqueColumnCombination ucc = new UniqueColumnCombination(List.of(object_id, o_attr));
        UniqueColumnCombination ucc2 = new UniqueColumnCombination(List.of(v_len));
        UniqueColumnCombination ucc3 = new UniqueColumnCombination(List.of(x_len));
        Assertions.assertEquals(0.5, ucc.positionScore());
        Assertions.assertEquals(1.0, ucc2.positionScore());
        Assertions.assertEquals(0.666, ucc3.positionScore(), 0.01);
    }


    @Test
    void nameSuffixScoreTest() {
        UniqueColumnCombination ucc = new UniqueColumnCombination(List.of(object_id, o_attr));
        UniqueColumnCombination ucc2 = new UniqueColumnCombination(List.of(object_id));
        UniqueColumnCombination ucc3 = new UniqueColumnCombination(List.of(v_len));
        Assertions.assertEquals(0.5, ucc.nameSuffixScore());
        Assertions.assertEquals(1.0, ucc2.nameSuffixScore());
        Assertions.assertEquals(0.0, ucc3.nameSuffixScore() );
    }
}
