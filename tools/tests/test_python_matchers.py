"""Bundled algorithms reproduce frozen reference matrices without external repositories."""
import json
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import urllib.request
from types import SimpleNamespace
from unittest.mock import patch
from http.server import HTTPServer
import numpy as np

PROJECT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(PROJECT/'src/main/resources/python_matchers'))
import seed_server
import study_server
from matcher_fixtures import make_scenario


class BundledMatchersTest(unittest.TestCase):
    def test_pded_requires_explicit_installation_without_searching_neighboring_repositories(self):
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, 'options.pded_home or SCHEMATCH_PDED'):
                study_server.m_pded({}, {})

    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.scenario,self.fixture=make_scenario(Path(self.temp.name))

    def tearDown(self):
        self.temp.cleanup()

    def request(self,method='dep',options=None):
        return {'method':method,'options':options or {},'scenario_path':str(self.scenario),
                'source_labels':self.fixture['rows'],'target_labels':self.fixture['cols'],'base':self.fixture['base']}

    def test_all_scenario_methods_match_reference_matrices(self):
        for case in self.fixture['scenario_cases']:
            with self.subTest(method=case['method'],options=case['options']):
                result=study_server.compute(self.request(case['method'],case['options']))
                np.testing.assert_allclose(result,case['expected'],rtol=0,atol=1e-14)

    def test_built_in_pair_methods_match_reference_matrices(self):
        request={'source_path':str(self.scenario/'source/p.csv'),'target_path':str(self.scenario/'target/q.csv'),
                 'source_table':'p','target_table':'q','source_labels':['id','group','label'],
                 'target_labels':['key','type','title'],'options':{}}
        for case in self.fixture['seed_cases']:
            with self.subTest(method=case['method']):
                result=seed_server.compute({**request,'method':case['method']})
                np.testing.assert_allclose(result,case['expected'],rtol=0,atol=1e-14)

    def test_default_order_handling_is_invariant(self):
        request=self.request('entity_alignment')
        expected=study_server.compute(request)
        rows=np.asarray([5,1,3,2,0,4]);cols=np.asarray([2,0,5,4,1,3])
        request['source_labels']=[self.fixture['rows'][i] for i in rows]
        request['target_labels']=[self.fixture['cols'][i] for i in cols]
        request['base']=np.asarray(self.fixture['base'])[np.ix_(rows,cols)].tolist()
        np.testing.assert_allclose(study_server.compute(request),expected[np.ix_(rows,cols)],rtol=0,atol=0)

    def test_scenario_http_matches_in_process(self):
        server=HTTPServer(('127.0.0.1',0),study_server.Handler)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            request=self.request('dep')
            url=f'http://127.0.0.1:{server.server_address[1]}/match'
            query=urllib.request.Request(url,data=json.dumps(request).encode(),headers={'Content-Type':'application/json'})
            with urllib.request.urlopen(query,timeout=30) as response:
                matrix=np.asarray([[float(value) for value in line.split()] for line in response.read().decode().splitlines()])
            np.testing.assert_allclose(matrix,study_server.compute(request),rtol=0,atol=5e-9)
        finally:
            server.shutdown();server.server_close();thread.join(timeout=5)

    def test_isresmat_invokes_an_installed_module_without_a_checkout(self):
        def installed_cli(command,**kwargs):
            output=next(item.split('=',1)[1] for item in command if item.startswith('--export-matrix='))
            Path(output).write_text('Source,key\nid,1\n')
            self.assertNotIn('PYTHONPATH',kwargs['env'])
            self.assertEqual(command[1:3],['-m','isresmat'])
            self.assertEqual(Path(output).parent,Path(kwargs['cwd']))
            return SimpleNamespace(returncode=0,stdout='',stderr='')
        request={'source_path':'source.csv','target_path':'target.csv','source_table':'source','target_table':'target',
                 'source_labels':['id'],'target_labels':['key']}
        with patch('subprocess.run',side_effect=installed_cli),patch.object(seed_server,'log_isresmat_attempt'):
            result=seed_server.m_isresmat(request,{'python':sys.executable,'gpu_attempts':0})
        np.testing.assert_array_equal(result,[[1.]])

    def test_magneto_uses_the_bundled_worker_in_an_explicit_environment(self):
        with patch('subprocess.run',return_value=SimpleNamespace(returncode=0,stdout='[[0.8]]',stderr='')) as run:
            result=seed_server.m_magneto({}, {'python':sys.executable})
        self.assertEqual(Path(run.call_args.args[0][1]).parent,Path(seed_server.__file__).parent)
        self.assertNotIn('PYTHONPATH',run.call_args.kwargs['env'])
        np.testing.assert_array_equal(result,[[.8]])

    def test_simple_pair_methods_are_bounded_and_label_identity_is_one(self):
        request={'source_path':str(self.scenario/'source/p.csv'),'target_path':str(self.scenario/'target/q.csv'),
                 'source_labels':['id','group','label'],'target_labels':['key','type','title'],'options':{}}
        sm=seed_server.bundled_module('simple_matchers')
        for method in list(sm.LABEL_METHODS)+list(sm.VALUE_METHODS):
            with self.subTest(method=method):
                result=seed_server.compute({**request,'method':method})
                self.assertEqual(result.shape,(3,3))
                self.assertTrue(np.all((result>=0)&(result<=1+1e-12)))
        for method in sm.LABEL_METHODS:
            with self.subTest(method=method):
                same=seed_server.compute({**request,'method':method,'target_labels':request['source_labels']})
                np.testing.assert_allclose(np.diag(same),1.0)
        # q repeats p's values under other names: value evidence must find the identity
        for method in ('tfidf_values','topk_values','dumas'):
            with self.subTest(method=method):
                self.assertEqual(list(seed_server.compute({**request,'method':method}).argmax(1)),[0,1,2])

    def test_resource_methods_fail_clearly_without_their_resources(self):
        request={'source_path':str(self.scenario/'source/p.csv'),'target_path':str(self.scenario/'target/q.csv'),
                 'source_labels':['id','group','label'],'target_labels':['key','type','title'],'options':{}}
        clean={k:v for k,v in __import__('os').environ.items() if k not in
               {'SCHEMATCH_BDIKIT_CL_CHECKPOINT','SCHEMATCH_ROBERTA','SCHEMATCH_SNOOPY','SCHEMATCH_FASTTEXT'}}
        with patch.dict('os.environ',clean,clear=True):
            for method in ('bdikit_cl','snoopy'):
                with self.subTest(method=method),self.assertRaises(FileNotFoundError):
                    seed_server.compute({**request,'method':method})

    def test_omnimatch_release_requires_configured_vectors(self):
        request=self.request(); request.pop('base')
        clean={k:v for k,v in __import__('os').environ.items() if k not in {'SCHEMATCH_FASTTEXT','SCHEMATCH_FASTTEXT_CACHE'}}
        with patch.dict('os.environ',clean,clear=True),self.assertRaises(FileNotFoundError):
            study_server.compute({**request,'method':'omnimatch_release','options':{}})

    def test_snoopy_projection_matches_the_released_forward(self):
        from schematch_py import snoopy
        rng=np.random.default_rng(0)
        P=rng.normal(size=(4,3,5)).astype(np.float32);X=rng.normal(size=(6,5)).astype(np.float32)
        cells=[f'c{i}' for i in range(6)]
        class WV(dict):
            vector_size=5
        vectors=WV({c:X[i] for i,c in enumerate(cells)})
        got=snoopy.column_embedding(cells,vectors,P,1000,rng)
        Xn=X/np.linalg.norm(X,axis=1,keepdims=True)   # one-word cells: the sentence vector is the normalised word vector
        t=np.einsum('hmd,nd->hmn',P,Xn).max(axis=2).sum(axis=1)
        np.testing.assert_allclose(got,t/np.linalg.norm(t),rtol=1e-5)

    def test_bdikit_variants_are_sent_to_the_magneto_worker(self):
        for method,variant in (('bdikit_max_val_sim','max_val_sim'),('bdikit_two_phase','two_phase'),('bdikit_two_phase_coma','two_phase')):
            with self.subTest(method=method),patch('subprocess.run',return_value=SimpleNamespace(returncode=0,stdout='[[0.5]]',stderr='')) as run:
                seed_server.METHODS[method]({}, {'python':sys.executable})
                sent=json.loads(run.call_args.kwargs['input'])
                self.assertEqual((sent['backend'],sent['options']['variant']),('magneto',variant))

    def test_standalone_scenario_methods_need_no_base_and_are_deterministic(self):
        from schematch_py import holistic
        def fake_embed(texts,options):
            rng=[np.random.default_rng(sum(map(ord,t))%(2**32)).normal(size=16) for t in texts]
            out=np.asarray(rng,dtype=np.float32)
            return out/np.linalg.norm(out,axis=1,keepdims=True)
        request=self.request()
        request.pop('base')
        with patch.object(holistic,'embed',side_effect=fake_embed),patch.object(holistic,'roberta_embeddings',side_effect=fake_embed):
            # omnimatch_release needs configured vectors; pded needs a PDED build and one table per side.
            for method in sorted(study_server.BASE_FREE-{'omnimatch_release','pded'}):
                with self.subTest(method=method):
                    if method in {'omnimatch','ract'} and importlib.util.find_spec('torch') is None:
                        self.skipTest('Optional learned-model test requires torch; no model downloads are used')
                    first=study_server.compute({**request,'method':method,'options':{'epochs':5}})
                    again=study_server.compute({**request,'method':method,'options':{'epochs':5}})
                    self.assertEqual(first.shape,(len(self.fixture['rows']),len(self.fixture['cols'])))
                    self.assertTrue(np.all((first>=0)&(first<=1+1e-6)))
                    np.testing.assert_allclose(first,again,rtol=0,atol=1e-6)


    def test_pded_requires_a_configured_build(self):
        request=self.request()
        request.pop('base')
        with tempfile.TemporaryDirectory() as empty:
            with self.assertRaises(FileNotFoundError):
                study_server.compute({**request,'method':'pded','options':{'pded_home':empty}})


if __name__=='__main__':
    unittest.main()
