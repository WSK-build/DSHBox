import argparse
import pathlib
import platform
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--ndk', type=pathlib.Path, required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[4]
host = {'Windows': 'windows-x86_64', 'Linux': 'linux-x86_64', 'Darwin': 'darwin-x86_64'}[platform.system()]
clang = args.ndk / 'toolchains/llvm/prebuilt' / host / 'bin' / ('clang.exe' if platform.system() == 'Windows' else 'clang')
output = root / 'app/src/main/jniLibs/arm64-v8a/libdshbox-lock.so'
subprocess.run([str(clang), '--target=aarch64-linux-android29', '-static', '-Oz', '-Wl,--strip-all', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384', '-Wall', '-Wextra', '-Werror', str(pathlib.Path(__file__).with_name('dsh_lock.c')), '-o', str(output)], check=True)
print(output.name, output.stat().st_size)
