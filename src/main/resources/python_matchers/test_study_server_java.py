"""Check Java-to-Python dispatch on a generated local multi-table scenario."""
from pathlib import Path
import argparse
import csv
import json
import os
import subprocess
import sys
import tempfile
import threading
from http.server import ThreadingHTTPServer
import numpy as np

PROJECT=Path(__file__).resolve().parents[4]
sys.path.insert(0,str(PROJECT/"tools/tests"))
from matcher_fixtures import make_scenario
import study_server


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath",type=Path,default=PROJECT/"target/cp.txt")
    args=parser.parse_args()
    cp=str(PROJECT/"target/classes")+os.pathsep+args.classpath.read_text().strip()
    class TestServer(ThreadingHTTPServer):
        def handle_error(self,request,client_address):
            # A finished JVM can reset an idle keep-alive connection on Windows.
            if not isinstance(sys.exc_info()[1],ConnectionResetError):
                super().handle_error(request,client_address)
    server=TestServer(("127.0.0.1",0),study_server.Handler)
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try:
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            scenario,fixture=make_scenario(root)
            seeds=root/"seeds"
            for method in study_server.IV:
                path=seeds/method/"Fixture/case/seed.csv"
                path.parent.mkdir(parents=True)
                with path.open("w",newline="",encoding="utf-8") as stream:
                    writer=csv.writer(stream)
                    writer.writerow(["Source\\Target",*fixture["cols"]])
                    writer.writerows([[label,*values] for label,values in zip(fixture["rows"],fixture["base"])])
            for method in study_server.METHODS:
                options={"base_recipe":"iv","seed_root":str(seeds)}
                output=root/(method+".txt")
                done=subprocess.run(["java","-cp",cp,"de.uni_marburg.schematch.matching.external.ScenarioPythonServerMatcherCheck",
                                     str(scenario),str(server.server_address[1]),method,json.dumps(options),str(output)],
                                    cwd=PROJECT,capture_output=True,text=True,timeout=60)
                if done.returncode:
                    raise RuntimeError(done.stdout+done.stderr)
                lines=output.read_text().splitlines()
                rows,cols=lines[0].split("\t"),lines[1].split("\t")
                actual=np.asarray([[float(v) for v in line.split()] for line in lines[2:]])
                expected=study_server.compute({"method":method,"options":options,"scenario_path":str(scenario),
                                              "source_labels":rows,"target_labels":cols,"base":None})
                np.testing.assert_allclose(actual,expected,rtol=0,atol=2e-7)
                print(method,"Java/Python parity passed")
    finally:
        server.shutdown();server.server_close();thread.join(timeout=5)
    return 0


if __name__=="__main__":
    raise SystemExit(main())
