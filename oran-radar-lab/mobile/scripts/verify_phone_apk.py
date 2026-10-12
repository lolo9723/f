"""Python 3.13: verify packaged on-device code matches the source being delivered."""
import io
import marshal
import pathlib
import sys
import types
import zipfile

assert sys.version_info[:2] == (3, 13), 'Use RADAR_BUILD_PYTHON (Python 3.13)'
source = pathlib.Path(__file__).resolve().parents[2] / 'backend_next' / 'radar'

def structure(code):
    def constant(value):
        if isinstance(value, types.CodeType):
            return structure(value)
        if isinstance(value, tuple):
            return tuple(constant(x) for x in value)
        return value
    return (code.co_argcount, code.co_posonlyargcount, code.co_kwonlyargcount,
            code.co_flags, code.co_code, code.co_names, code.co_varnames,
            code.co_freevars, code.co_cellvars, tuple(constant(x) for x in code.co_consts))

with zipfile.ZipFile(sys.argv[1]) as apk:
    with zipfile.ZipFile(io.BytesIO(apk.read('assets/chaquopy/app.imy'))) as app:
        checked = 0
        for path in source.glob('*.py'):
            name = 'radar/' + path.stem + '.pyc'
            assert name in app.namelist(), name
            data = app.read(name)
            actual = marshal.loads(data[16:])
            assert isinstance(actual, types.CodeType), name
            assert any(structure(actual) == structure(compile(path.read_text(), actual.co_filename, 'exec', optimize=level))
                       for level in (0, 1, 2)), f'Packaged source differs: {name}'
            checked += 1
        print(f'Phone Python code matches current source: {checked} modules')
    assert 'assets/chaquopy/requirements-arm64-v8a.imy' in apk.namelist()
