"""Small relational fixtures, generated locally without external datasets."""
import csv
import json
from pathlib import Path

FIXTURE = Path(__file__).resolve().parent/'fixtures/matcher_reference.json'


def make_scenario(root):
    tables={'source':{'p':[['id','group','label']]+[[str(i),str(i%3),'group'+str(i%3)] for i in range(30)],
                      'v':[['id','person','amount']]+[[str(i+100),str(i),str(i%5)] for i in range(30)]},
            'target':{'q':[['key','type','title']]+[[str(i),str(i%3),'group'+str(i%3)] for i in range(30)],
                      'w':[['key','owner','sum']]+[[str(i+100),str(i),str(i%5)] for i in range(30)]}}
    scenario=root/'Fixture/case'
    for side,relations in tables.items():
        for name,rows in relations.items():
            folder=scenario/side
            folder.mkdir(parents=True,exist_ok=True)
            with (folder/(name+'.csv')).open('w',encoding='utf-8',newline='') as stream:
                csv.writer(stream).writerows(rows)
            metadata=scenario/'metadata'/side/name
            metadata.mkdir(parents=True,exist_ok=True)
            (metadata/'UCC_results.txt').write_text(f'[{name}.csv.{rows[0][0]}]\n')
            fds=f'[{name}.csv.{rows[0][1]}] --> {name}.csv.{rows[0][2]} (pdep 1.0, 0.8)\n' if name in {'p','q'} else ''
            (metadata/'FD_results.txt').write_text(fds)
        (scenario/'metadata'/side/'inds.txt').write_text(
            '[v.csv.person] [= [p.csv.id]\n' if side=='source' else '[w.csv.owner] [= [q.csv.key]\n')
    return scenario,json.loads(FIXTURE.read_text())
