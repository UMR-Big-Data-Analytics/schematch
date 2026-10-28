package de.uni_marburg.schematch.data.metadata.dependency;

import de.uni_marburg.schematch.data.Table;
import de.uni_marburg.schematch.utils.InputReader;
import de.uni_marburg.schematch.utils.MetadataUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MetanomeCache{

    public static List<UniqueColumnCombination> executeUCC(List<Table> tables) {
        List<UniqueColumnCombination> uccs = new ArrayList<>();
        try {
            for (Table table : tables) {
                Path uccPath = MetadataUtils.getMetadataPathFromTable(Path.of(table.getPath())).resolve("UCC_results.txt");
                uccs.addAll(InputReader.readUCCFile(uccPath, table, new HashMap<>()));
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return uccs;
    }

    public static List<FunctionalDependency> executeFD(List<Table> tables) {
        List<FunctionalDependency> fds = new ArrayList<>();
        try {
            for (Table table : tables) {
                Path fdPath = MetadataUtils.getMetadataPathFromTable(Path.of(table.getPath())).resolve("FD_results.txt");
                fds.addAll(InputReader.readFDFile(fdPath, table, new HashMap<>()));

            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return fds;
    }

    public static List<InclusionDependency> executeIND(List<Table> tables) {
        List<InclusionDependency> inds;
        try {
            Path indPath = MetadataUtils.getMetadataRootPathFromTable(Path.of(tables.get(0).getPath())).resolve("inds.txt");
            inds = new ArrayList<>(InputReader.readINDFile(indPath, tables, tables, new HashMap<>()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return inds;
    }
}
