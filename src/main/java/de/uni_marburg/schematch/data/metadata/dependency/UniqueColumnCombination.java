package de.uni_marburg.schematch.data.metadata.dependency;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UniqueColumnCombination implements Dependency {
    Collection<Column> columnCombination; //Unique Column Combination //FD is most minimal

    public double getPrimaryKeyScore() {
        double lengthScore = this.cardinalityScore();
        double valueScore = this.valueLengthScore();
        double positionScore = this.positionScore();
        double nameSuffixScore = this.nameSuffixScore();

        return (lengthScore + valueScore + positionScore + nameSuffixScore) / 4.0;
    }

    public double cardinalityScore() { //range (0,1]: 1 is best because most keys have few attributes, 0 is worst
        if (columnCombination.isEmpty()) {
            return 0.0;
        }
        return 1.0 / (double) columnCombination.size();
    }

    public double valueLengthScore() { //range (0,1]: 1 is best because most keys have smaller values, 0 is worst
        int n = 8; //Max Length of values at which a primary Key should be punished

//        int sumLength = 0;
//
//        //To also work on approximate UCCs iterate over all values
//        for(Column column : columnCombination) {
//            sumLength = sumLength + getLongestValue(column).length();
//        }
//
//        double maxX = (double) sumLength / (double) columnCombination.size();
//
//        return 1 / Math.max(1, maxX - n);

        double scoreSum = 0;

        for (Column column : columnCombination) {
            double valueLengthScore = calculateValueLengthScoreForColumn(column, n);
            scoreSum += valueLengthScore;
        }

        return scoreSum / columnCombination.size();
    }

    private double calculateValueLengthScoreForColumn(Column column, int n) {
        int maxX = column.getValues().stream().mapToInt(String::length).max().orElse(Integer.MAX_VALUE);
        return 1.0 / Math.max(1, maxX - n);
    }

    public double positionScore() { //range (0,1]: 1 is best because most keys are at the beginning of a table and for multi-column keys there are no other attributes between them, 0 is worst

        Table table = columnCombination.iterator().next().getTable();

        //Find minimum Left Index
        int minIndexLeft = Integer.MAX_VALUE;
        int maxIndexRight = Integer.MIN_VALUE;

        for (Column column : columnCombination) {
            if (table.getColumnIndex(column) < minIndexLeft) {
                minIndexLeft = table.getColumnIndex(column);
            }
            if (table.getColumnIndex(column) > maxIndexRight) {
                maxIndexRight = table.getColumnIndex(column);
            }
        }

        Collection<Column> attributesLeftOfUCC = new HashSet<>();

        for (int i = 0; i < minIndexLeft; i++) {
            attributesLeftOfUCC.add(table.getColumn(i));
        }

        Collection<Column> attributesBetweenMinLeftAndMaxRightOfUCC = new HashSet<>();

        for (int i = minIndexLeft; i <= maxIndexRight; i++) {
            attributesBetweenMinLeftAndMaxRightOfUCC.add(table.getColumn(i));
        }

        attributesBetweenMinLeftAndMaxRightOfUCC.removeAll(columnCombination);

        int leftX = attributesLeftOfUCC.size();
        int betweenX = attributesBetweenMinLeftAndMaxRightOfUCC.size();

        return 0.5 * ((1.0 / (leftX + 1)) + (1.0 / (betweenX + 1)));
    }

    public double nameSuffixScore() { //range: [0,1]: 1 is best as all columns in key have a common key suffix, 0 is worst

        Set<String> suffixes = new HashSet<>(List.of("id", "key", "nr", "no"));

        int count = 0;

        for (Column column : columnCombination) {

            String columnLabel = column.getLabel().toLowerCase();

            for (String suffix : suffixes) {
                if (columnLabel.endsWith(suffix)) { //TODO: Contains oder endsWith?
                    count++;
                }
            }
        }

        return (double) count / (double) columnCombination.size();
    }

    @SuppressWarnings("unused")
    private String getLongestValue(Column column) {
        return column.getValues().stream().max(Comparator.comparingInt(String::length)).orElse("");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UniqueColumnCombination that = (UniqueColumnCombination) o;
        return Objects.equals(columnCombination, that.columnCombination);
    }

    @Override
    public int hashCode() {
        return Objects.hash(columnCombination);
    }

    @Override
    public String toString() {
        return Util.columnsToString(columnCombination);
    }

}
