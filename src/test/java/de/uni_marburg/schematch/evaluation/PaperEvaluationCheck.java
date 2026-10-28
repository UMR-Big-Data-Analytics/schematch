package de.uni_marburg.schematch.evaluation;

import de.uni_marburg.schematch.data.*;
import de.uni_marburg.schematch.matching.similarity.instance.PMFMatcher;
import de.uni_marburg.schematch.matching.refinement.HungarianBonusMatcher;
import de.uni_marburg.schematch.matchtask.MatchTask;
import de.uni_marburg.schematch.matchtask.matchstep.*;
import de.uni_marburg.schematch.matchtask.tablepair.generators.NaiveTablePairsGenerator;
import de.uni_marburg.schematch.utils.Configuration;
import java.nio.file.Path;
import java.util.List;

/** Opt-in end-to-end check with a real configured Python; not part of native-only JUnit requirements. */
public final class PaperEvaluationCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("PaperEvaluationCheck <dataset> <output> <python-executable>");
        Configuration c = Configuration.getInstance();
        c.setReadDependencies(false); c.setPaperEvaluation(true); c.setPaperEvaluationPython(args[2]);
        c.setEvaluateTablePairGeneration(false); c.setEvaluateFirstLineMatchers(false); c.setEvaluateSecondLineMatchers(false);
        c.setEvaluateSimMatrixBoostingOnFirstLineMatchers(false); c.setEvaluateSimMatrixBoostingOnSecondLineMatchers(false);
        c.setSaveOutputFirstLineMatchers(false); c.setSaveOutputSecondLineMatchers(false);
        c.setReadCacheFirstLineMatchers(false); c.setReadCacheSecondLineMatchers(false);
        c.setWriteCacheFirstLineMatchers(false); c.setWriteCacheSecondLineMatchers(false);
        PMFMatcher base = new PMFMatcher();
        HungarianBonusMatcher refinement = new HungarianBonusMatcher();
        List<MatchStep> steps = List.of(new TablePairGenerationStep(false,false,new NaiveTablePairsGenerator()),
                new MatchingStep(false,false,1,List.of(base)),new MatchingStep(false,false,2,List.of(refinement)));
        Dataset dataset = new Dataset(new Configuration.DatasetConfiguration("toy",Path.of(args[0]).toAbsolutePath().toString()));
        PaperEvaluationRecorder.start(Path.of(args[1]));
        for (String name : dataset.getScenarioNames()) new MatchTask(dataset,
                new Scenario(Path.of(dataset.getPath()).resolve(name).toString()),steps,List.of()).runSteps();
        PaperEvaluationRecorder.finish();
    }
}
