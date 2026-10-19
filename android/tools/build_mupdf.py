"""Build the pinned, minimally patched Android JNI; works on Windows and Linux."""
from pathlib import Path
import ctypes, hashlib, io, json, os, shutil, subprocess, tarfile, zipfile
import patch_mupdf

ROOT = Path(__file__).resolve().parents[1]
NDK_VERSION = '27.2.12479018'

def toolpath(path):
    path = Path(path).resolve()
    if os.name == 'nt':
        buf = ctypes.create_unicode_buffer(32768)
        if not ctypes.windll.kernel32.GetShortPathNameW(str(path), buf, len(buf)):
            raise RuntimeError('Não foi possível obter o caminho curto para o NDK.')
        if ' ' in buf.value:
            raise RuntimeError('O NDK precisa de caminhos sem espaços neste Windows.')
        return buf.value.replace('\\', '/')
    return str(path)

def build(abis, download, cache, out, lock):
    sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', ROOT.parent / '.tools/android-sdk')))
    ndk = sdk / 'ndk' / NDK_VERSION
    if not (ndk / 'source.properties').is_file():
        raise RuntimeError(f'Instale o NDK: sdkmanager "ndk;{NDK_VERSION}"')
    spec = lock['mupdf']
    archive_path = download(spec['source_url'], spec['source_sha256'], 'mupdf-1.28.5-source.tar.gz')
    source = cache / 'mupdf-src'
    with tarfile.open(archive_path, 'r:gz') as archive:
        if not (source / 'platform/java/Android.mk').is_file():
            source.mkdir(parents=True, exist_ok=True)
            for member in archive:
                parts = member.name.split('/')[1:]
                if not parts or '..' in parts or not member.isfile():
                    continue
                target = source.joinpath(*parts)
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as inp, target.open('wb') as dest:
                    shutil.copyfileobj(inp, dest)
        patch_mupdf.apply(archive, source)
    obj = ROOT / 'app/build/native-obj'; obj.mkdir(parents=True, exist_ok=True)
    libs = ROOT / 'app/build/native-libs'; libs.mkdir(parents=True, exist_ok=True)
    prefix = '-ffile-prefix-map=' + toolpath(source) + '=/mupdf'
    command = [toolpath(ndk / ('ndk-build.cmd' if os.name == 'nt' else 'ndk-build')),
        'NDK_PROJECT_PATH=null', 'APP_BUILD_SCRIPT=' + toolpath(source / 'platform/java/Android.mk'),
        'NDK_APPLICATION_MK=' + toolpath(ROOT / 'native/Application.mk'),
        'NDK_OUT=' + toolpath(obj), 'NDK_LIBS_OUT=' + toolpath(libs),
        'USE_EXTRACT=yes', 'USE_TESSERACT=', 'USE_LEPTONICA=',
        'MUPDF_EXTRA_CFLAGS=' + prefix + ' -DFZ_ENABLE_JS=0', 'MUPDF_EXTRA_CPPFLAGS=' + prefix,
        '-j' + str(min(os.cpu_count() or 2, 8))]
    for abi in abis:
        print('Building patched MuPDF ' + spec['version'] + ' for ' + abi, flush=True)
        subprocess.run([*command, 'APP_ABI=' + abi], cwd=toolpath(source), check=True)
        target = out / 'jniLibs' / abi / 'libmupdf_java.so'
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(libs / abi / 'libmupdf_java.so', target)
    # Keep the exact upstream Java API. The compiled/patched JNI replaces ALL
    # binary JNI members of the official AAR, avoiding duplicate/stock libraries.
    aar = cache / f"fitz-{spec['version']}.aar"
    data = io.BytesIO()
    with zipfile.ZipFile(aar) as original, zipfile.ZipFile(data, 'w', zipfile.ZIP_DEFLATED) as clean:
        for entry in original.infolist():
            if not entry.filename.startswith('jni/'):
                clean.writestr(entry, original.read(entry))
    (out / 'fitz.aar').write_bytes(data.getvalue())
    meta = {'engine': 'MuPDF ' + spec['version'], 'ndk': NDK_VERSION, 'source_sha256': spec['source_sha256'],
        'patch_sha256': hashlib.sha256(Path(patch_mupdf.__file__).read_bytes()).hexdigest(),
        'libraries': {abi: hashlib.sha256((out/'jniLibs'/abi/'libmupdf_java.so').read_bytes()).hexdigest() for abi in abis}}
    (out / 'assets/native-build.json').write_text(json.dumps(meta, indent=2), encoding='utf-8')
    notice = ndk / 'NOTICE'
    if notice.exists():
        target = out / 'assets/licenses/Android-NDK-NOTICE.txt'; target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(notice, target)
