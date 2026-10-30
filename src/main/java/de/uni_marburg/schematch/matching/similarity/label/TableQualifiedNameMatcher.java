package de.uni_marburg.schematch.matching.similarity.label;

import de.uni_marburg.schematch.matching.TablePairMatcher;
import de.uni_marburg.schematch.matchtask.tablepair.TablePair;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** X105 Q: token Jaccard of table and column names with camel-case splitting and simple plural stripping. */
public final class TableQualifiedNameMatcher extends TablePairMatcher {
    public static Set<String> tokens(String table, String column) {
        Set<String> tokens = new HashSet<>();
        for (String part : new String[]{table, column}) {
            String split = part.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                    .replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2").toLowerCase(Locale.ROOT);
            for (String token : split.split("[^a-z0-9]+")) {
                if (!token.isEmpty()) tokens.add(token.length() > 3 && token.endsWith("s")
                        ? token.substring(0, token.length() - 1) : token);
            }
        }
        return tokens;
    }

    public static float similarity(String leftTable, String leftColumn, String rightTable, String rightColumn) {
        Set<String> left = tokens(leftTable, leftColumn), right = tokens(rightTable, rightColumn);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        if (union.isEmpty()) return 0;
        left.retainAll(right);
        return (float) left.size() / union.size();
    }

    @Override
    public float[][] match(TablePair pair) {
        float[][] out = pair.getEmptySimMatrix();
        for (int i = 0; i < out.length; i++) {
            for (int j = 0; j < out[i].length; j++) {
                out[i][j] = similarity(pair.getSourceTable().getName(), pair.getSourceTable().getColumn(i).getLabel(),
                        pair.getTargetTable().getName(), pair.getTargetTable().getColumn(j).getLabel());
            }
        }
        return out;
    }
}
