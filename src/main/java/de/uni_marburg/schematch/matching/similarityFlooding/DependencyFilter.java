package de.uni_marburg.schematch.matching.similarityFlooding;

import de.uni_marburg.schematch.data.Column;
import de.uni_marburg.schematch.data.metadata.Datatype;
import de.uni_marburg.schematch.data.metadata.dependency.FunctionalDependency;
import de.uni_marburg.schematch.data.metadata.dependency.InclusionDependency;
import de.uni_marburg.schematch.data.metadata.dependency.UniqueColumnCombination;
import lombok.Getter;
import lombok.Setter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Setter
@Getter
public class DependencyFilter {
    private static final Logger log = LogManager.getLogger(DependencyFilter.class);

    private final String uccFilterThreshold;
    private final String indFilterThreshold;
    private final String fdFilterThreshold;
    private final int topK;
    // The perturbation settings of this filter: matcher parameters where set, else the properties below
    // (GraphOptions).
    private final double retention;
    private final long retentionSeed;
    private final boolean rewire;
    private final RewireMode rewireMode;
    private final double wTarget;
    private final String uccTopK;

    DependencyFilter(String uccFilterThreshold, String indFilterThreshold, String fdFilterThreshold) {
        this(uccFilterThreshold, indFilterThreshold, fdFilterThreshold, "");
    }

    DependencyFilter(String uccFilterThreshold, String indFilterThreshold, String fdFilterThreshold, String dependencyTopK) {
        this(uccFilterThreshold, indFilterThreshold, fdFilterThreshold, dependencyTopK, GraphOptions.defaults());
    }

    DependencyFilter(String uccFilterThreshold, String indFilterThreshold, String fdFilterThreshold, String dependencyTopK,
                     GraphOptions options) {
        this.uccFilterThreshold = uccFilterThreshold;
        this.indFilterThreshold = indFilterThreshold;
        this.fdFilterThreshold = fdFilterThreshold;
        this.topK = (dependencyTopK == null || dependencyTopK.isBlank())
                ? Integer.MAX_VALUE
                : Integer.parseInt(dependencyTopK.trim());
        this.retention = options.fdRetention;
        this.retentionSeed = options.fdRetentionSeed;
        this.rewire = options.fdRewire;
        this.rewireMode = RewireMode.valueOf(options.fdRewireMode);
        this.wTarget = options.fdWTarget;
        this.uccTopK = options.uccTopK;
    }

    /**
     * Fraction of the surviving functional dependencies to retain, chosen at
     * random. 1.0, the default, retains all of them and is a no-op.
     *
     * This exists to separate two things the corpus confounds. FD density
     * (`fds / nodes`) separates TPC-DI from OpenData 99.3% of the time
     * (FINDINGS 96), but the two families barely overlap in density, so
     * "density predicts the response" cannot be told apart from "family
     * predicts the response". `fdFilterThreshold` does vary density within a
     * scenario, but it removes the *weakest* dependencies first, so quantity
     * and genuineness move together -- which is PRISMA's point about accidental
     * dependencies, and is a confound rather than a control.
     *
     * Random thinning changes only how many dependencies reach the graph. Rows,
     * columns, values, column names, the ground truth and the lexical
     * initialisation are all untouched, so a scenario can serve as its own
     * control and a dense scenario can be thinned into a sparse one's density
     * range.
     *
     * Sampling is by exact count rather than by independent coin flips, so the
     * realised density is a known function of the retention rate instead of a
     * binomial draw around it. The collection is sorted by {@code toString}
     * first, because the dependencies arrive from a hash set and the iteration
     * order is otherwise not reproducible across runs.
     */
    static final double RETENTION =
            Double.parseDouble(System.getProperty("smx.sf.fdRetention", "1.0"));

    /** Replicate index for the thinning draw; changing it redraws the subset. */
    static final long RETENTION_SEED =
            Long.parseLong(System.getProperty("smx.sf.fdRetentionSeed", "0"));

    /**
     * Negative control: keep the number of dependencies and the arity profile,
     * but resample which columns they connect.
     *
     * Thinning alone cannot distinguish "the matcher responds to how much
     * dependency evidence there is" from "the matcher responds to what the
     * dependencies say". Rewiring holds the first fixed and destroys the
     * second. If the response survives rewiring it is about edge count and
     * topology; if it disappears, the dependencies' content is what mattered.
     */
    static final boolean REWIRE =
            Boolean.parseBoolean(System.getProperty("smx.sf.fdRewire", "false"));

    /**
     * How a rewire draws its replacement dependencies.
     *
     * RANDOM, the historical behaviour, resamples each determinant and dependant
     * uniformly from the column pool. It preserves the edge count and each
     * dependency's arity and nothing else -- in particular it changes per-column
     * in- and out-degree, and therefore path multiplicity through the
     * propagation graph. A difference against a thinned arm under RANDOM
     * confounds "the dependencies meant something" with "the graph had a
     * different shape", which is why the first rewire result is reported as
     * undecidable between those.
     *
     * EDGE is the strictest of the three and the one a pairing experiment needs.
     * It keeps each dependency's **(dependant, arity) pair** exactly as the real
     * set had it, and randomises only which columns fill the determinant slots.
     * Because a column's edge in-degree is the sum of the arities of the
     * dependencies it is the dependant of, fixing those pairs fixes the edge
     * in-degree; redistributing the slot pool fixes the out-degree; so both
     * marginals of the *projected* graph are preserved, not merely the
     * dependency-level counts. What varies is the wiring alone.
     *
     * DEGREE preserves less than its name suggests, which is why EDGE exists.
     * It fixes the multiset of dependants and the multiset of determinant slots
     * but not which arity is paired with which dependant, so a column that was
     * the dependant of three arity-1 dependencies can become the dependant of
     * three arity-5 ones and triple its edge in-degree. Measured on this corpus
     * (mean arity 1.64), DEGREE preserves the edge-level two-walk count on 1
     * scenario in 60 (`SF_TWO_WALK_MEASUREMENT.md`). Read it as
     * *dependency-degree-preserving*.
     *
     * DEGREE is a configuration-model rewire. It keeps, exactly:
     *   - the number of dependencies,
     *   - each dependency's determinant arity,
     *   - how many times each column occurs as a dependant,
     *   - how many determinant slots each column fills,
     * and destroys only which determinant is paired with which dependant. Every
     * column therefore keeps its in- and out-degree in the dependency channel,
     * so a difference under DEGREE is attributable to the pairing -- the FD
     * identities -- rather than to the degree sequence.
     */
    enum RewireMode { RANDOM, DEGREE, EDGE }

    static final RewireMode REWIRE_MODE = RewireMode.valueOf(
            System.getProperty("smx.sf.fdRewireMode", "RANDOM").toUpperCase());

    /**
     * Applies random thinning and, optionally, rewiring.
     *
     * Order matters: thinning runs first so that a rewired arm and a thinned
     * arm at the same retention carry the same number of dependencies and are
     * comparable at matched density.
     */
    /**
     * Target pairing between in- and out-degree, in [-1, +1]; NaN, the default,
     * leaves the dependency set alone.
     *
     * The identity
     *   W = sum_v d_in(v) d_out(v) = n * Cov(d_in, d_out) + m^2 / n
     * says that once n, m and the two degree *multisets* are fixed, varying W is
     * exactly varying the pairing between a node's in- and out-degree. This
     * knob is that manipulation and nothing else.
     *
     * It works by relabelling dependants through a permutation pi: every
     * dependency X -> A becomes X -> pi(A). Determinants are untouched, so every
     * out-degree is preserved exactly; pi is a bijection on the columns, so the
     * multiset of in-degrees is preserved exactly; arities, edge count and node
     * count are all untouched. The only thing that moves is which in-degree sits
     * on which out-degree.
     *
     * pi is built by rank alignment. Order the columns by out-degree and by
     * in-degree; +1 pairs rank k with rank k (co-sorted, maximal W by the
     * rearrangement inequality), -1 pairs rank k with rank n+1-k (anti-sorted,
     * minimal W), 0 pairs at random (independent). Intermediate magnitudes start
     * from the extreme and apply a proportion of random transpositions.
     *
     * Note that the *real* graph is not a rung of this ladder. Its rungs are all
     * synthetic, so semantic identity is not bundled into the W manipulation --
     * every rung has had its dependant labels permuted, and they differ only in
     * how.
     */
    static final double W_TARGET =
            Double.parseDouble(System.getProperty("smx.sf.fdWTarget", "NaN"));

    /** A determinant slot is bad if it repeats a column or names the dependant. */
    private static boolean violates(List<Column> determinant, int slot, Column dependant) {
        Column c = determinant.get(slot);
        if (c.equals(dependant)) {
            return true;
        }
        for (int i = 0; i < determinant.size(); i++) {
            if (i != slot && determinant.get(i).equals(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Relabels dependants so that in-degree pairs with out-degree at the target
     * rank alignment, leaving both degree multisets and every arity untouched.
     */
    private List<FunctionalDependency> wLadder(List<FunctionalDependency> thinned,
                                               java.util.Random rng) {
        java.util.Map<Column, Long> in = new java.util.HashMap<>();
        java.util.Map<Column, Long> out = new java.util.HashMap<>();
        for (FunctionalDependency fd : thinned) {
            in.merge(fd.getDependant(), (long) fd.getDeterminant().size(), Long::sum);
            for (Column c : fd.getDeterminant()) {
                out.merge(c, 1L, Long::sum);
            }
        }
        // Permute only among the columns that are already dependants. Those are
        // exactly the columns with d_in > 0, so they are the only ones that
        // contribute to W = sum_v d_in(v) d_out(v) -- control over them is full
        // control over W. Permuting across all columns instead would let a
        // column that was only ever a dependant drop to degree zero and leave
        // the graph, changing the node count; measured, that happened on up to
        // two thirds of scenarios and the identity above depends on n.
        Set<Column> nodeSet = new HashSet<>(in.keySet());
        List<Column> byOut = new ArrayList<>(nodeSet);
        List<Column> byIn = new ArrayList<>(nodeSet);
        // Deterministic ties: the column name breaks equal degrees, so the same
        // inputs and seed always give the same ladder.
        byOut.sort(java.util.Comparator
                .comparingLong((Column c) -> out.getOrDefault(c, 0L))
                .thenComparing(Column::toString));
        byIn.sort(java.util.Comparator
                .comparingLong((Column c) -> in.getOrDefault(c, 0L))
                .thenComparing(Column::toString));

        int n = byOut.size();
        List<Column> image = new ArrayList<>(java.util.Collections.nCopies(n, (Column) null));
        double t = Math.max(-1.0, Math.min(1.0, wTarget));
        if (t >= 0) {
            for (int k = 0; k < n; k++) {
                image.set(k, byOut.get(k));                 // co-sorted: max W
            }
        } else {
            for (int k = 0; k < n; k++) {
                image.set(k, byOut.get(n - 1 - k));         // anti-sorted: min W
            }
        }
        // Decorrelate toward independence in proportion to 1 - |t|.
        int swaps = (int) Math.round((1.0 - Math.abs(t)) * n);
        for (int i = 0; i < swaps; i++) {
            int a = rng.nextInt(n), b = rng.nextInt(n);
            Column tmp = image.get(a); image.set(a, image.get(b)); image.set(b, tmp);
        }
        java.util.Map<Column, Column> pi = new java.util.HashMap<>();
        for (int k = 0; k < n; k++) {
            pi.put(byIn.get(k), image.get(k));
        }

        List<List<Column>> dets = new ArrayList<>(thinned.size());
        List<Column> deps = new ArrayList<>(thinned.size());
        for (FunctionalDependency fd : thinned) {
            dets.add(new ArrayList<>(fd.getDeterminant()));
            deps.add(pi.get(fd.getDependant()));
        }
        int repaired = 0, unresolved = 0;
        for (int k = 0; k < dets.size(); k++) {
            for (int slot = 0; slot < dets.get(k).size(); slot++) {
                if (!violates(dets.get(k), slot, deps.get(k))) {
                    continue;
                }
                boolean fixed = false;
                for (int attempt = 0; attempt < 64 && !fixed; attempt++) {
                    int j = rng.nextInt(dets.size());
                    if (j == k || dets.get(j).isEmpty()) {
                        continue;
                    }
                    int other = rng.nextInt(dets.get(j).size());
                    Column a = dets.get(k).get(slot), b = dets.get(j).get(other);
                    dets.get(k).set(slot, b); dets.get(j).set(other, a);
                    if (!violates(dets.get(k), slot, deps.get(k))
                            && !violates(dets.get(j), other, deps.get(j))) {
                        fixed = true; repaired++;
                    } else {
                        dets.get(k).set(slot, a); dets.get(j).set(other, b);
                    }
                }
                if (!fixed) { unresolved++; }
            }
        }
        List<FunctionalDependency> laddered = new ArrayList<>(thinned.size());
        for (int k = 0; k < thinned.size(); k++) {
            laddered.add(new FunctionalDependency(dets.get(k), deps.get(k)));
        }
        log.info("FD W-ladder target={} nodes={} swaps={} repaired={} unresolved={} retention={} seed={}",
                t, n, swaps, repaired, unresolved, retention, retentionSeed);
        return laddered;
    }

    private Collection<FunctionalDependency> perturb(Collection<FunctionalDependency> kept) {
        if (retention >= 1.0 && !rewire && Double.isNaN(wTarget)) {
            return kept;
        }
        List<FunctionalDependency> ordered = new ArrayList<>(kept);
        ordered.sort(java.util.Comparator.comparing(FunctionalDependency::toString));
        java.util.Random rng = new java.util.Random(
                retentionSeed * 1000003L + ordered.size());
        java.util.Collections.shuffle(ordered, rng);

        int take = retention >= 1.0
                ? ordered.size()
                : (int) Math.floor(ordered.size() * retention);
        List<FunctionalDependency> thinned = new ArrayList<>(ordered.subList(0, take));

        if (!Double.isNaN(wTarget)) {
            if (rewire) {
                throw new IllegalArgumentException(
                        "smx.sf.fdWTarget and smx.sf.fdRewire are different manipulations "
                                + "of the same dependency set. Set one or the other.");
            }
            return wLadder(thinned, rng);
        }

        if (!rewire) {
            log.info("FD thinning: {} -> {} (retention {}, seed {})",
                    kept.size(), thinned.size(), retention, retentionSeed);
            return thinned;
        }

        // The pool a rewired dependency may draw from is exactly the columns the
        // real dependencies already touch, so rewiring cannot introduce a column
        // the graph did not already have a node for.
        List<Column> pool = new ArrayList<>(new java.util.LinkedHashSet<>(
                thinned.stream()
                        .flatMap(fd -> java.util.stream.Stream.concat(
                                fd.getDeterminant().stream(),
                                java.util.stream.Stream.of(fd.getDependant())))
                        .collect(Collectors.toList())));
        pool.sort(java.util.Comparator.comparing(Column::toString));

        if (rewireMode == RewireMode.EDGE) {
            // Keep (dependant, arity) fixed; reshuffle the determinant slots.
            List<Column> slots = new ArrayList<>();
            for (FunctionalDependency fd : thinned) {
                slots.addAll(fd.getDeterminant());
            }
            java.util.Collections.shuffle(slots, rng);

            List<List<Column>> dets = new ArrayList<>(thinned.size());
            int cursor = 0;
            for (FunctionalDependency fd : thinned) {
                int arity = fd.getDeterminant().size();
                dets.add(new ArrayList<>(slots.subList(cursor, cursor + arity)));
                cursor += arity;
            }

            // A shuffle can put a column twice in one determinant, or make a
            // column determine itself. Both are repaired by *swapping* a slot
            // with a slot in another dependency, which moves a column between
            // determinants and therefore leaves every out-degree untouched --
            // and touches no dependant or arity, so every edge in-degree is
            // untouched too. Bounded, because a pathological set may not be
            // repairable; whatever remains is logged rather than hidden.
            int repaired = 0, unresolved = 0;
            for (int k = 0; k < dets.size(); k++) {
                for (int slot = 0; slot < dets.get(k).size(); slot++) {
                    if (!violates(dets.get(k), slot, thinned.get(k).getDependant())) {
                        continue;
                    }
                    boolean fixed = false;
                    for (int attempt = 0; attempt < 64 && !fixed; attempt++) {
                        int j = rng.nextInt(dets.size());
                        if (j == k || dets.get(j).isEmpty()) {
                            continue;
                        }
                        int other = rng.nextInt(dets.get(j).size());
                        Column a = dets.get(k).get(slot);
                        Column b = dets.get(j).get(other);
                        dets.get(k).set(slot, b);
                        dets.get(j).set(other, a);
                        if (!violates(dets.get(k), slot, thinned.get(k).getDependant())
                                && !violates(dets.get(j), other, thinned.get(j).getDependant())) {
                            fixed = true;
                            repaired++;
                        } else {
                            dets.get(k).set(slot, a);
                            dets.get(j).set(other, b);
                        }
                    }
                    if (!fixed) {
                        unresolved++;
                    }
                }
            }

            List<FunctionalDependency> edgeRewired = new ArrayList<>(thinned.size());
            for (int k = 0; k < thinned.size(); k++) {
                edgeRewired.add(new FunctionalDependency(dets.get(k), thinned.get(k).getDependant()));
            }
            log.info("FD thinning+edge-rewire: {} -> {} (repaired {}, unresolved {}, "
                            + "retention {}, seed {})",
                    kept.size(), edgeRewired.size(), repaired, unresolved,
                    retention, retentionSeed);
            return edgeRewired;
        }

        if (rewireMode == RewireMode.DEGREE) {
            // Configuration model. Pool the dependant slots and the determinant
            // slots separately, shuffle each pool, and deal them back out in the
            // original arity pattern. Each column reappears exactly as often as
            // it did on each side, so the degree sequence is preserved by
            // construction rather than by rejection sampling.
            List<Column> dependants = new ArrayList<>();
            List<Column> slots = new ArrayList<>();
            List<Integer> arities = new ArrayList<>();
            for (FunctionalDependency fd : thinned) {
                dependants.add(fd.getDependant());
                slots.addAll(fd.getDeterminant());
                arities.add(fd.getDeterminant().size());
            }
            java.util.Collections.shuffle(dependants, rng);
            java.util.Collections.shuffle(slots, rng);

            List<FunctionalDependency> degreeRewired = new ArrayList<>(thinned.size());
            int cursor = 0;
            int selfPairs = 0;
            int dupSlots = 0;
            for (int k = 0; k < thinned.size(); k++) {
                int arity = arities.get(k);
                List<Column> determinant = new ArrayList<>(
                        slots.subList(cursor, Math.min(cursor + arity, slots.size())));
                cursor += arity;
                Column dependant = dependants.get(k);
                // A column determining itself contributes a self-loop the real
                // dependency set never has. Counted and left in place rather
                // than resampled, because resampling would break the degree
                // guarantee this mode exists to provide.
                if (determinant.contains(dependant)) {
                    selfPairs++;
                }
                // Dealing slots blindly can place the same column twice in one
                // determinant, which a discovered FD never has. Counted rather
                // than repaired, for the same reason as the self-pairs: removing
                // it would break the degree guarantee. If it is common the
                // control is weaker than it claims and that has to be visible.
                if (new java.util.HashSet<>(determinant).size() < determinant.size()) {
                    dupSlots++;
                }
                degreeRewired.add(new FunctionalDependency(determinant, dependant));
            }
            log.info("FD thinning+degree-rewire: {} -> {} (self-pairs {}, dup-slots {}, "
                            + "retention {}, seed {})",
                    kept.size(), degreeRewired.size(), selfPairs, dupSlots, retention, retentionSeed);
            return degreeRewired;
        }

        List<FunctionalDependency> rewired = new ArrayList<>(thinned.size());
        for (FunctionalDependency fd : thinned) {
            int arity = fd.getDeterminant().size();
            if (pool.size() <= arity) {
                rewired.add(fd);
                continue;
            }
            // Draw the dependant first, then an arity-sized determinant from the
            // rest, so no dependency determines a column inside its own
            // determinant.
            Column dependant = pool.get(rng.nextInt(pool.size()));
            List<Column> rest = new ArrayList<>(pool);
            rest.remove(dependant);
            java.util.Collections.shuffle(rest, rng);
            rewired.add(new FunctionalDependency(
                    new ArrayList<>(rest.subList(0, arity)), dependant));
        }
        log.info("FD thinning+rewire: {} -> {} over a {}-column pool (retention {}, seed {})",
                kept.size(), rewired.size(), pool.size(), retention, retentionSeed);
        return rewired;
    }

    /**
     * Keeps only the highest scoring dependencies.
     *
     * A score threshold does not bound how many dependencies reach the graph:
     * a schema with hundreds of near-identical FDs passes them all, and the
     * cost of matching grows with that count. Ranking instead gives a
     * deterministic budget, and keeps the dependencies the scores rate
     * highest rather than an arbitrary subset of those above a cut.
     */
    private <T> Collection<T> keepTopK(Collection<T> kept, java.util.function.ToDoubleFunction<T> score) {
        if (this.topK == Integer.MAX_VALUE || kept.size() <= this.topK) {
            return kept;
        }
        List<T> ranked = new ArrayList<>(kept);
        ranked.sort(java.util.Comparator.comparingDouble(score).reversed());
        return new ArrayList<>(ranked.subList(0, this.topK));
    }

    /**
     * Directed two-edge walks in the dependency graph, and the degree sequence
     * that fixes them.
     *
     * A functional dependency X -> A contributes one edge x -> A for each
     * x in X. So d_out(v) counts the determinant slots v fills and d_in(v)
     * counts the dependencies v is the dependant of, and the number of directed
     * two-edge walks is sum_v d_in(v) * d_out(v).
     *
     * This is the quantity `SF_ITERATION_DEPTH.md` names as the one measurement
     * its outcome licenses. A degree-preserving rewire holds every d_in and
     * d_out fixed and therefore cannot change this sum; a uniform rewire does
     * change it. The degree-sequence contrast appeared at exactly the propagation
     * depth at which two-edge walks first act, and this reports the statistic
     * that prediction rests on so it can be checked rather than assumed.
     */
    private static void reportWalkStats(Collection<FunctionalDependency> fds) {
        if (!Boolean.getBoolean("smx.sf.printWalkStats")) {
            return;
        }
        // Two definitions of in-degree, and they are not the same statistic.
        //
        // A dependency X -> A projects onto |X| edges x -> A, so A's *edge*
        // in-degree is the sum of the arities of the dependencies it depends
        // on, not the number of them. The first version of this instrumentation
        // used the dependency count, which coincides with the edge in-degree
        // only when every arity is 1. Both are reported here: fdIn is what was
        // measured first, edgeIn is the one that belongs to the projected graph
        // and to the identity W = n*Cov(d_in,d_out) + m^2/n, which requires
        // sum d_in = sum d_out = m.
        //
        // This distinction also decides how much a degree-preserving rewire is
        // really preserving. The rewire fixes the multiset of dependants and the
        // multiset of determinant slots, so fdIn and out are preserved by
        // construction. It does NOT fix which arity is paired with which
        // dependant, so edgeIn can move. Reporting both makes that visible
        // instead of assumed.
        java.util.Map<Column, Integer> fdIn = new java.util.HashMap<>();
        java.util.Map<Column, Integer> edgeIn = new java.util.HashMap<>();
        java.util.Map<Column, Integer> out = new java.util.HashMap<>();
        for (FunctionalDependency fd : fds) {
            int arity = fd.getDeterminant().size();
            fdIn.merge(fd.getDependant(), 1, Integer::sum);
            edgeIn.merge(fd.getDependant(), arity, Integer::sum);
            for (Column c : fd.getDeterminant()) {
                out.merge(c, 1, Integer::sum);
            }
        }
        Set<Column> nodes = new HashSet<>(out.keySet());
        nodes.addAll(fdIn.keySet());
        // Sufficient statistics for covariance and correlation over all nodes,
        // counting a node that appears on only one side as degree 0 on the
        // other -- which is what the identity requires.
        long sIn = 0, sOut = 0, sIn2 = 0, sOut2 = 0, sInOut = 0;
        long eIn = 0, eIn2 = 0, eInOut = 0;
        for (Column c : nodes) {
            long fi = fdIn.getOrDefault(c, 0);
            long ei = edgeIn.getOrDefault(c, 0);
            long o = out.getOrDefault(c, 0);
            sIn += fi; sOut += o; sIn2 += fi * fi; sOut2 += o * o; sInOut += fi * o;
            eIn += ei; eIn2 += ei * ei; eInOut += ei * o;
        }
        System.out.println("WALKSTATS2 fds=" + fds.size()
                + " nodes=" + nodes.size()
                + " sum_fdin=" + sIn + " sum_out=" + sOut + " sum_edgein=" + eIn
                + " sq_fdin=" + sIn2 + " sq_out=" + sOut2 + " sq_edgein=" + eIn2
                + " w_fd=" + sInOut + " w_edge=" + eInOut);
    }

    Collection<FunctionalDependency> filterFunctionalDependencies(Collection<FunctionalDependency> functionalDependencies) {

        List<FunctionalDependency> filteredFDs = new ArrayList<>();

        double threshold = Double.parseDouble(this.fdFilterThreshold);

        for (FunctionalDependency fd : functionalDependencies) {

            //Determinant should have at least one attribute
            //Maximum determinant size of 3 (because large determinant often appear by chance)
            if (!fd.getDeterminant().isEmpty() && fd.getDeterminant().size() <= 3) {
                double score = fd.calculateNGPDEPScore();

                if(score >= threshold) {
                    filteredFDs.add(fd);
                }
            }
        }

        Collection<FunctionalDependency> rankedFDs = keepTopK(filteredFDs, FunctionalDependency::calculateNGPDEPScore);
        rankedFDs = perturb(rankedFDs);
        reportWalkStats(rankedFDs);
        log.info("Filtered FDs: {} -> {}", functionalDependencies.size(), rankedFDs.size());

        return rankedFDs;
    }

    Collection<UniqueColumnCombination> filterUniqueColumnCombinations(Collection<UniqueColumnCombination> uniqueColumnCombinations) {

        Collection<UniqueColumnCombination> filteredUCCs = new HashSet<>();

        double threshold = Double.parseDouble(this.uccFilterThreshold);

        for (UniqueColumnCombination ucc : uniqueColumnCombinations) {

            if (!ucc.getColumnCombination().isEmpty() && ucc.getColumnCombination().size() <= 3) { //(Primary) Keys very rarely consist of more than 3 columns
                double score = ucc.getPrimaryKeyScore();

                if (score >= threshold) {
                    filteredUCCs.add(ucc);
                }
            }
        }

        // B's dev copy (sf-matcher-study/devjava_b, exploratory X16): -Dsmx.sf.uccTopK=k keeps the k best UCCs by
        // primary-key score, independently of dependencyTopK (which also cuts FDs). Off by default.
        String uccTopKProp = this.uccTopK;
        Collection<UniqueColumnCombination> rankedUCCs;
        if (!uccTopKProp.isBlank()) {
            List<UniqueColumnCombination> byScore = new ArrayList<>(filteredUCCs);
            byScore.sort(java.util.Comparator.comparingDouble(UniqueColumnCombination::getPrimaryKeyScore).reversed());
            int k = Integer.parseInt(uccTopKProp.trim());
            rankedUCCs = new ArrayList<>(byScore.subList(0, Math.min(k, byScore.size())));
        } else {
            rankedUCCs = keepTopK(filteredUCCs, UniqueColumnCombination::getPrimaryKeyScore);
        }
        log.info("Filtered UCCs: {} -> {}", uniqueColumnCombinations.size(), rankedUCCs.size());

        return rankedUCCs;
    }

    Collection<InclusionDependency> filterInclusionDependencies(Collection<InclusionDependency> inclusionDependencies) {

        Collection<InclusionDependency> filteredINDs = new HashSet<>();

        double threshold = Double.parseDouble(this.indFilterThreshold);

        for (InclusionDependency ind : inclusionDependencies) {

            if (!ind.getDependant().isEmpty() && ind.getDependant().size() <= 3) { //Foreign Key Constraints very rarely consist of more than 3 attributes

                Set<Datatype> datatypesInIND = ind.getReferenced().stream().map(Column::getDatatype).collect(Collectors.toSet());

                if (!datatypesInIND.contains(Datatype.BOOLEAN)) { //No boolean column should be part of the ind, as these should not be a part of any keys
                    double score = ind.getForeignKeyScore();

                    if (score >= threshold) {
                        filteredINDs.add(ind);
                    }
                }
            }
        }

        Collection<InclusionDependency> rankedINDs = keepTopK(filteredINDs, InclusionDependency::getForeignKeyScore);
        log.info("Filtered INDs: {} -> {}", inclusionDependencies.size(), rankedINDs.size());

        return rankedINDs;
    }

}
