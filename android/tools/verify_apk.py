"""Check packaged ABI, ELF page alignment and LibreOffice asset storage (stdlib)."""
from pathlib import Path
import argparse
import hashlib
import json
import struct
import zipfile


def inspect(apk, abi):
    expected_machine = {'arm64-v8a': 183, 'x86_64': 62}[abi]
    libraries = []
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None, 'APK corrompido'
        for entry in archive.infolist():
            if entry.filename.startswith(('assets/program/', 'assets/share/', 'assets/unpack/')):
                assert entry.compress_type == zipfile.ZIP_STORED, f'Ativo LO comprimido: {entry.filename}'
            if entry.filename.startswith('lib/') and entry.filename.endswith('.so'):
                assert entry.filename.split('/')[1] == abi, f'ABI inesperada: {entry.filename}'
                data = archive.read(entry)
                assert data[:6] == b'\x7fELF\x02\x01', 'ELF 64-bit little endian esperado'
                machine = struct.unpack_from('<H', data, 18)[0]
                assert machine == expected_machine, f'Máquina ELF inválida: {entry.filename}'
                phoff = struct.unpack_from('<Q', data, 32)[0]
                size, count = struct.unpack_from('<HH', data, 54)
                loads = [struct.unpack_from('<IIQQQQQQ', data, phoff + i * size)
                         for i in range(count) if struct.unpack_from('<I', data, phoff + i * size)[0] == 1]
                assert loads and min(item[-1] for item in loads) >= 16384, f'Alinhamento ELF menor que 16 KB: {entry.filename}'
                libraries.append({'name': entry.filename, 'elf_machine': machine, 'min_load_alignment': min(item[-1] for item in loads)})
        names = {item['name'].rsplit('/', 1)[-1] for item in libraries}
        assert {'liblo-native-code.so', 'libmupdf_java.so'} <= names, 'Motor ausente do APK'
        assert archive.getinfo('assets/unpack/etc/fonts/fonts.conf').file_size > 0, 'Fontconfig ausente'
        native = json.loads(archive.read('assets/native-build.json'))
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    return {'apk': apk.name, 'bytes': apk.stat().st_size, 'sha256': digest,
            'abi': abi, 'libraries': libraries, 'native_build': native}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--abi', choices=['arm64-v8a', 'x86_64'], required=True)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    result = inspect(args.apk, args.abi)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(f"OK: {result['apk']}, {result['abi']}, {result['bytes']} bytes, {len(result['libraries'])} ELF alinhados a 16 KB")
