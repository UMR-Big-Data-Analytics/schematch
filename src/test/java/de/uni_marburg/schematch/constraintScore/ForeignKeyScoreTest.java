package de.uni_marburg.schematch.constraintScore;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.data.metadata.dependency.InclusionDependency;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

public class ForeignKeyScoreTest {

    static Column t1_customer_id = new Column("customer_id", List.of("1", "2", "3", "4"));
    static Column t1_branch_id = new Column("branch_id", List.of("101", "102", "103", "104"));
    static Column t1_name = new Column("name", List.of("Alice", "Bob", "Charlie", "David"));
    Table customers = new Table("Customers", List.of("customer_id", "branch_id", "name"), List.of(t1_customer_id, t1_branch_id, t1_name), null, null);

    static Column t2_order_id = new Column("order_id", List.of("101", "102", "103", "104", "105"));
    static Column t2_customer_id = new Column("customer_id", List.of("1", "1", "2", "3", "4"));
    static Column t2_branch_id = new Column("branch_id", List.of("101", "101", "102", "103", "104"));
    static Column t2_order_date = new Column("order_date", List.of("2024-03-01", "2024-03-05", "2024-03-10", "2024-03-12", "2024-03-15"));
    Table orders = new Table("Orders", List.of("order_id", "branch_id", "customer_id", "order_date"), List.of(t2_order_id, t2_branch_id, t2_customer_id, t2_order_date), null, null);

    static Column t3_order_id = new Column("order_id", List.of("101", "103", "104"));
    static Column t3_customer_id = new Column("customer_id", List.of("1" ,"2", "3"));
    static Column t3_branch_id = new Column("branch_id", List.of("101", "102", "103"));
    static Column t3_order_date = new Column("order_date", List.of("2024-03-01", "2024-03-05", "2024-03-10"));
    Table orders2 = new Table("Orders", List.of("order_id", "branch_id", "customer_id", "order_date"), List.of(t3_order_id, t3_branch_id, t3_customer_id, t3_order_date), null, null);



    Collection<Column> primaryKey = List.of(t1_customer_id, t1_branch_id);
    Collection<Column> primaryKey2 = List.of(t2_customer_id, t2_order_id);
    Collection<Column> primaryKey3 = List.of(t3_customer_id, t3_order_id);
    Collection<Column> primaryKey4 = List.of(t2_order_date, t2_customer_id);
    Collection<Column> primaryKey5 = List.of(t2_branch_id);
    Collection<Column> primaryKey6 = List.of(t3_branch_id);

    Collection<Column> foreignKey = List.of(t2_branch_id, t2_customer_id);
    Collection<Column> foreignKey2 = List.of(t3_branch_id, t3_customer_id);
    Collection<Column> foreignKey3 = List.of(t3_customer_id, t3_order_id);
    Collection<Column> foreignKey4 = List.of(t2_order_date, t3_branch_id);
    Collection<Column> foreignKey5 = List.of(t2_order_date, t2_customer_id);
    Collection<Column> foreignKey6 = List.of(t2_order_date, t2_branch_id);
    Collection<Column> foreignKey7 = List.of(t2_customer_id);
    Collection<Column> foreignKey8 = List.of(t3_order_id);


    @Test
    void coverageScoreTest() {
        InclusionDependency ind = new InclusionDependency(primaryKey, foreignKey);
        InclusionDependency ind2 = new InclusionDependency(primaryKey, foreignKey2);
        InclusionDependency ind3 = new InclusionDependency(primaryKey2, foreignKey3);
        InclusionDependency ind4 = new InclusionDependency(primaryKey2, foreignKey2);

        Assertions.assertEquals(1.0, ind.coverageScore());
        Assertions.assertEquals(0.75, ind2.coverageScore());
        Assertions.assertEquals(0.6, ind3.coverageScore());
        Assertions.assertEquals(0.0, ind4.coverageScore());
    }

    @Test
    void columnSimilarityScoreTest() {
        InclusionDependency ind1 = new InclusionDependency(primaryKey, foreignKey);
        InclusionDependency ind2 = new InclusionDependency(primaryKey, foreignKey2);
        InclusionDependency ind3 = new InclusionDependency(primaryKey3, foreignKey2);
        InclusionDependency ind4 = new InclusionDependency(primaryKey3, foreignKey4);

        Assertions.assertEquals(0.6, ind1.columnNameSimilarityScore(), 0.001);
        Assertions.assertTrue(ind1.columnNameSimilarityScore() > 0.5); //3,5,7,9,10
        Assertions.assertTrue(ind2.columnNameSimilarityScore() > 0.5);
        Assertions.assertTrue(ind3.columnNameSimilarityScore() < 0.4);
        Assertions.assertTrue(ind4.columnNameSimilarityScore() < 0.4);
        Assertions.assertTrue(ind3.columnNameSimilarityScore() > ind4.columnNameSimilarityScore());


    }

    @Test
    void valueLengthDifferenceScoreTest() {
        InclusionDependency ind = new InclusionDependency(primaryKey, foreignKey);
        InclusionDependency ind2 = new InclusionDependency(primaryKey, foreignKey5);
        InclusionDependency ind3 = new InclusionDependency(primaryKey4, foreignKey6);
        InclusionDependency ind4 = new InclusionDependency(primaryKey5, foreignKey7);
        InclusionDependency ind5 = new InclusionDependency(primaryKey5, foreignKey6);


        Assertions.assertEquals(1.0, ind.valueLengthDifferenceScore());
        Assertions.assertEquals(0.41, ind2.valueLengthDifferenceScore(), 0.1);
        Assertions.assertEquals(0.85, ind3.valueLengthDifferenceScore(), 0.1);
        Assertions.assertEquals(0.31, ind4.valueLengthDifferenceScore(), 0.1);
        Assertions.assertEquals(0.21, ind5.valueLengthDifferenceScore(), 0.1);

    }

    @Test
    void outOfRangeScoreTest() {
        InclusionDependency ind = new InclusionDependency(primaryKey, foreignKey);
        InclusionDependency ind2 = new InclusionDependency(primaryKey, foreignKey2);
        InclusionDependency ind3 = new InclusionDependency(primaryKey, foreignKey3);

        InclusionDependency ind4 = new InclusionDependency(primaryKey2, foreignKey3);
        InclusionDependency ind5 = new InclusionDependency(foreignKey3, primaryKey2);

        InclusionDependency ind6 = new InclusionDependency(primaryKey6, foreignKey8);
        InclusionDependency ind7 = new InclusionDependency(foreignKey8, primaryKey6);

        Assertions.assertEquals(1.0, ind.outOfRangeScore(), 0.1);
        Assertions.assertEquals(1.0, ind2.outOfRangeScore(), 0.1);
        Assertions.assertEquals(0.0, ind3.outOfRangeScore(), 0.1);

        Assertions.assertEquals(1.0, ind4.outOfRangeScore(), 0.1);
        Assertions.assertEquals(0.6, ind5.outOfRangeScore(), 0.1);

        Assertions.assertEquals(0.66, ind6.outOfRangeScore(), 0.1);
        Assertions.assertEquals(0.66, ind7.outOfRangeScore(), 0.1);
    }

    @Test
    void foreignKeyScoreTest() {
        InclusionDependency ind = new InclusionDependency(primaryKey, foreignKey);
        InclusionDependency ind2 = new InclusionDependency(primaryKey, foreignKey3);
        InclusionDependency ind3 = new InclusionDependency(primaryKey2, foreignKey3);
        InclusionDependency ind4 = new InclusionDependency(primaryKey2, foreignKey2);
        InclusionDependency ind5 = new InclusionDependency(primaryKey, foreignKey2);
        InclusionDependency ind6 = new InclusionDependency(primaryKey2, foreignKey5);


        Assertions.assertEquals(0.9, ind.getForeignKeyScore(), 0.01);

        Assertions.assertEquals(0.35, ind2.getForeignKeyScore(), 0.01);
        Assertions.assertEquals(0.8, ind3.getForeignKeyScore(), 0.01);
        Assertions.assertTrue(ind2.getForeignKeyScore() < ind3.getForeignKeyScore());

        Assertions.assertEquals(0.35, ind4.getForeignKeyScore(), 0.01);
        Assertions.assertEquals(0.84, ind5.getForeignKeyScore(), 0.01);
        Assertions.assertTrue(ind4.getForeignKeyScore() < ind5.getForeignKeyScore());

        Assertions.assertEquals(0.24, ind6.getForeignKeyScore(), 0.01);
    }


}
