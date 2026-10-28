# Bundled example

The default configuration runs PMF on the six-row synthetic pair in `data/demo/`.
The source and target have three columns with distinct frequency distributions;
the truth maps identifier to key, category to kind, and flag to status. These
tables were created for this example and contain no benchmark or personal data.

From the project root:

```shell
mvn test compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
python tools/run_schematch.py
```

The output matrix is written below `results/`. No Python matcher service,
downloaded dataset, model, or mined dependency profile is needed.
