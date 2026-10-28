package de.uni_marburg.schematch.matching.ensemble;

/** Named recipes share the ordinary CompositeMatcher implementation and inherited YAML parameters. */
public abstract class NamedCompositeMatcher extends CompositeMatcher {
    @Override
    public String toString() {
        return getClass().getSimpleName() + "(components=" + getComponents() + ", aggregation=" + getAggregation()
                + ", normalise=" + getNormalise() + ", predictor=" + getPredictor() + ")";
    }
}
