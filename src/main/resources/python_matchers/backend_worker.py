"""Run an optional installed backend in an explicitly selected interpreter."""
import contextlib
import json
import sys


def main():
    payload=json.load(sys.stdin)
    if payload['backend']!='magneto':
        raise ValueError('Unsupported backend')
    with contextlib.redirect_stdout(sys.stderr):
        from seed_server import magneto_in_process
        result=magneto_in_process(payload['request'],payload['options'])
    json.dump(result.tolist(),sys.stdout,allow_nan=False)


if __name__=='__main__':
    main()
