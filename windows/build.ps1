$ErrorActionPreference = 'Stop'
python ../scripts/make_windows_icon.py
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
windres receiver.rc -O coff -o receiver-res.o
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
g++ receiver.cpp receiver-res.o -o MyCastReceiver.exe -std=c++17 -O2 -static -static-libgcc -static-libstdc++ -municode -mwindows -lws2_32 -lgdiplus -lshlwapi -lole32
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
